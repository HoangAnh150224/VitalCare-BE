#!/usr/bin/env node
// VitalCare device simulator (FE-03): sends readings the way a wristband would, over REST or MQTT.
//
//   node tools/device-simulator.mjs --code VC-BAND-01 --token <secret> [--via rest|mqtt]
//        [--api http://localhost:8085/api] [--broker mqtt://localhost:1883]
//        [--count 5] [--every 2] [--scenario normal|low_spo2|high_heart_rate|fever|sensor_fault]
//        [--offline 3]   hold the first N readings back and send them together, like a phone back online
//        [--repeat]      send the last batch a second time, to see duplicates refused
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
      client.publish(`vitalcare/devices/${code}/vitals`, JSON.stringify(upload), { qos: 1 }, (err) => err && reject(err));
      setTimeout(() => reject(new Error("no ack in 10 s")), 10_000);
    });
  close = () => client.endAsync();
} else {
  send = async (upload) => {
    const r = await fetch(`${api}/device_data/batch`, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Device-Code": code, "X-Device-Token": token },
      body: JSON.stringify(upload),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(`${r.status} ${body.message ?? ""}`);
    return body;
  };
}

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
await close();
