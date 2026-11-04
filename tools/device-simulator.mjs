#!/usr/bin/env node
// VitalCare device simulator (FE-03): sends readings the way a wristband would, over REST or MQTT.
//
//   node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> [--via rest|mqtt]
//        [--api http://localhost:8085/api] [--broker mqtt://localhost:1883]
//        [--count 5] [--every 2] [--scenario normal|low_spo2|high_heart_rate|fever|sensor_fault]
//        [--offline 3]   hold the first N readings back and send them together, like a phone back online
//        [--repeat]      send the last batch a second time, to see duplicates refused
//        [--csv file.csv [--shift now] [--batch 20] [--min-conf 80] [--raw]]  replay a band's "#v2" log (see replayCsv);
//                        --raw sends the log text itself (text/csv, or the MQTT payload) for the server to read
//
// The secret comes from the device's page ("Cấp mã bí mật") or POST /api/devices/{id}/token.
// MQTT needs the "mqtt" package: npm i mqtt.

import { randomUUID } from "node:crypto";
import { createRequire } from "node:module";

const args = Object.fromEntries(
  process.argv.slice(2).reduce((pairs, arg, i, all) => {
    if (arg.startsWith("--")) pairs.push([arg.slice(2), all[i + 1]?.startsWith("--") || all[i + 1] === undefined ? "true" : all[i + 1]]);
    return pairs;
  }, []),
);
const code = args.code;
const token = args.token;
if (!code || !token) {
  console.error("Usage: node tools/device-simulator.mjs --code <deviceCode> --token <secret> [--via rest|mqtt] ...");
  process.exit(1);
}
const via = (args.via ?? "rest").toLowerCase();
const api = args.api ?? "http://localhost:8085/api";
const broker = args.broker ?? "mqtt://localhost:1883";
const count = Number(args.count ?? 5);
const every = Number(args.every ?? 2);
const offline = Number(args.offline ?? 0);
const scenario = (args.scenario ?? "normal").toLowerCase();

const jitter = (base, spread) => Math.round((base + (Math.random() * 2 - 1) * spread) * 10) / 10;
const reading = (sessionId) => {
  const fault = scenario === "sensor_fault";
  return {
    clientRecordId: randomUUID(),
    measuredAt: new Date().toISOString(),
    spo2: fault ? 140 : Math.min(100, jitter(scenario === "low_spo2" ? 88 : 97, 1)),
    heartRate: fault ? 0 : Math.round(jitter(scenario === "high_heart_rate" ? 135 : 76, 3)),
    skinTemperature: jitter(scenario === "fever" ? 38.6 : 33.4, 0.2),
    sessionId,
  };
};

let send;
let close = () => {};
if (via === "mqtt") {
  // An installed "mqtt" package, wherever require() finds it (node_modules up the tree, or NODE_PATH).
  const { connectAsync } = await import("mqtt").catch(() => createRequire(import.meta.url)("mqtt"));
  const client = await connectAsync(broker, { username: code, password: token, clientId: `${code}-${randomUUID().slice(0, 8)}` });
  await client.subscribeAsync(`vitalcare/devices/${code}/ack`, { qos: 1 });
  const acks = [];
  client.on("message", (_topic, payload) => acks.shift()?.(JSON.parse(payload.toString())));
  send = (upload) =>
    new Promise((resolve, reject) => {
      acks.push(resolve);
      // An upload object goes as JSON; a band log (--raw) as the text it is.
      const payload = typeof upload === "string" ? upload : JSON.stringify(upload);
      client.publish(`vitalcare/devices/${code}/vitals`, payload, { qos: 1 }, (err) => err && reject(err));
      setTimeout(() => reject(new Error("no ack in 10 s")), 10_000);
    });
  close = () => client.endAsync();
} else {
  send = async (upload) => {
    const r = await fetch(`${api}/device_data/batch`, {
      method: "POST",
      headers: {
        "Content-Type": typeof upload === "string" ? "text/csv" : "application/json",
        "X-Device-Code": code,
        "X-Device-Token": token,
      },
      body: typeof upload === "string" ? upload : JSON.stringify(upload),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(`${r.status} ${body.message ?? ""}`);
    return body;
  };
}

if (args.csv) {
  await replayCsv(args.csv);
} else {
  await simulate();
}
await close();

/**
 * --csv <file>: replays a band's own log, format "#v2" — one line per measurement:
 *   ts        Unix seconds
 *   t100      skin temperature × 100 (3279 = 32.79 °C)
 *   hr, spo2  0 = no value from the sensor yet
 *   conf      confidence, %
 *   flags     the band's status bits, printed but not sent (32: no SpO₂; 36: no HR nor SpO₂)
 * Options: --shift now   move the series so its last line is now (keeps the spacing), for a band
 *                        handed over today; --shift N moves it N seconds; without it the lines keep their time
 *          --batch N     lines per upload (default 20); the rest are reported as pendingOnDevice
 *          --min-conf N  below this confidence, HR and SpO₂ are dropped (temperature kept)
 * Each line's id is "<code>-<ts>", so replaying the same file again stores nothing twice.
 */
async function replayCsv(path) {
  const { readFileSync } = await import("node:fs");
  const lines = readFileSync(path, "utf8").split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  if (lines[0] !== "#v2") throw new Error(`${path}: expected a "#v2" log, got "${lines[0]}"`);
  const header = lines[1].split(",");
  const col = (name) => {
    const i = header.indexOf(name);
    if (i < 0) throw new Error(`${path}: no "${name}" column`);
    return i;
  };
  const [TS, T100, HR, SPO2, CONF, FLAGS] = ["ts", "t100", "hr", "spo2", "conf", "flags"].map(col);
  const rows = lines.slice(2).map((l) => l.split(",").map(Number));
  const minConf = Number(args["min-conf"] ?? 0);
  // "now": the last line lands now; a number: that many seconds later — the same number twice is the
  // same log resent, which the server recognises (its ids come from ts).
  const shiftMs = args.shift === "now" ? Date.now() - rows.at(-1)[TS] * 1000 : Number(args.shift ?? 0) * 1000;
  if (args.shift === "now") console.log(`shift: ${Math.round(shiftMs / 1000)} s (pass --shift ${Math.round(shiftMs / 1000)} to resend this same log)`);
  const value = (v) => (v > 0 ? v : null);

  const readings = [];
  let skipped = 0;
  const sessionId = `${code}-${rows[0][TS]}`;
  for (const row of rows) {
    const trusted = row[CONF] >= minConf;
    const r = {
      clientRecordId: `${code}-${row[TS]}`,
      measuredAt: new Date(row[TS] * 1000 + shiftMs).toISOString(),
      heartRate: trusted ? value(row[HR]) : null,
      spo2: trusted ? value(row[SPO2]) : null,
      skinTemperature: value(row[T100]) == null ? null : row[T100] / 100,
      sessionId,
    };
    if (r.heartRate == null && r.spo2 == null && r.skinTemperature == null) {
      skipped++;
      continue;
    }
    if (row[FLAGS]) console.log(`  ${row[TS]} flags=${row[FLAGS]} conf=${row[CONF]} → hr ${r.heartRate ?? "—"}, SpO₂ ${r.spo2 ?? "—"}`);
    readings.push(r);
  }
  const size = Math.max(1, Number(args.batch ?? 20));
  // --raw: the server reads the log itself — each batch is "#v2", the header and its lines, as a band
  // would send it (with --shift now, the ts column moved like the readings above).
  if (args.raw) {
    const shiftS = Math.round(shiftMs / 1000);
    const data = lines.slice(2).map((l) => {
      const f = l.split(",");
      f[TS] = String(Number(f[TS]) + shiftS);
      return f.join(",");
    });
    for (let i = 0; i < data.length; i += size) {
      const result = await send(["#v2", lines[1], ...data.slice(i, i + size)].join(String.fromCharCode(10)));
      console.log(`raw batch ${i / size + 1} (${Math.min(size, data.length - i)} lines) ${via.toUpperCase()} → ${result.status}: ${result.accepted} kept, ${result.duplicates} repeats, ${result.invalid} invalid, ${result.rejected} refused`);
      for (const reason of new Set((result.records ?? []).filter((x) => x.reason).map((x) => `${x.outcome}: ${x.reason}`))) console.log(`  ${reason}`);
      if (i + size < data.length) await new Promise((r) => setTimeout(r, every * 1000));
    }
    return;
  }
  const startedAt = readings[0]?.measuredAt;
  let sent = 0;
  let lastUpload;
  let refusedForWearer = false;
  for (let i = 0; i < readings.length; i += size) {
    const chunk = readings.slice(i, i + size);
    sent += chunk.length;
    const done = sent === readings.length;
    lastUpload = {
      clientBatchId: `${code}-csv-${rows[0][TS]}-${i / size + 1}`,
      pendingOnDevice: readings.length - sent,
      firmware: "csv-v2",
      session: { clientSessionId: sessionId, startedAt, endedAt: done ? chunk.at(-1).measuredAt : null, status: done ? "COMPLETED" : "IN_PROGRESS" },
      readings: chunk,
    };
    const result = await send(lastUpload);
    console.log(`batch ${i / size + 1} (${chunk.length} lines) ${via.toUpperCase()} → ${result.status}: ${result.accepted} kept, ${result.duplicates} repeats, ${result.invalid} invalid, ${result.rejected} refused`);
    for (const reason of new Set(result.records.filter((x) => x.reason).map((x) => `${x.outcome}: ${x.reason}`))) console.log(`  ${reason}`);
    refusedForWearer ||= result.records.some((x) => x.reason?.includes("chưa được giao"));
    if (!done) await new Promise((r) => setTimeout(r, every * 1000));
  }
  console.log(`${rows.length} lines: ${readings.length} sent, ${skipped} without any value skipped`);
  if (refusedForWearer) {
    console.log("  Lines from before the band was handed over belong to nobody. Use --shift now, or hand the band over before the log's first line.");
  }
  if (args.repeat && lastUpload) {
    const result = await send(lastUpload);
    console.log(`repeat → ${result.status}: ${result.duplicates} repeats refused`);
  }
}

/** Generated readings for --scenario, --count, --every, --offline, --repeat. */
async function simulate() {
  const sessionId = randomUUID();
  const startedAt = new Date().toISOString();
  const held = [];
  let last;
  for (let i = 0; i < count; i++) {
    const r = reading(sessionId);
    const done = i === count - 1;
    // The first --offline readings wait, as on a phone out of signal, and go up with the next one.
    if (i < offline && !done) {
      held.push(r);
      console.log(`#${i + 1} held offline (${held.length} waiting)`);
    } else {
      const readings = [...held.splice(0), r];
      last = {
        clientBatchId: randomUUID(),
        pendingOnDevice: 0,
        battery: Math.max(5, 90 - i),
        firmware: "sim-1.0",
        session: { clientSessionId: sessionId, startedAt, endedAt: done ? new Date().toISOString() : null, status: done ? "COMPLETED" : "IN_PROGRESS" },
        readings,
      };
      const result = await send(last);
      console.log(`#${i + 1} ${via.toUpperCase()} → ${result.status}: ${result.accepted} kept, ${result.duplicates} repeats, ${result.invalid} invalid, ${result.rejected} refused`);
    }
    if (!done) await new Promise((r) => setTimeout(r, every * 1000));
  }
  if (args.repeat && last) {
    const result = await send(last);
    console.log(`repeat → ${result.status}: ${result.duplicates} repeats refused`);
  }
}
