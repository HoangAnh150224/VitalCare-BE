#!/usr/bin/env node
// Registers VitalCare's SMS templates with eSMS (they are then reviewed by eSMS before they can be sent).
// https://developers.esms.vn/esms-api/ham-truy-xuat-va-dang-ky/ham-dang-ky-template-sms
//
// Usage (the keys come from the environment, never from this file):
//   ESMS_API_KEY=... ESMS_SECRET_KEY=... node scripts/esms-register-templates.mjs           # SmsType 8, fixed number
//   ESMS_API_KEY=... ESMS_SECRET_KEY=... ESMS_BRANDNAME=Xyz node scripts/esms-register-templates.mjs --type 2
//   ... node scripts/esms-register-templates.mjs --dry-run      # prints what would be sent, sends nothing
//
// CodeResult 100 means eSMS received the template and is reviewing it, not that it is approved.
// Once approved, put the exact approved text in ESMS_TEMPLATE / ESMS_BOOKING_TEMPLATE / ESMS_DESK_TEMPLATE.

const TEMPLATES = [
  { env: "ESMS_TEMPLATE", content: "{code} la ma xac minh VitalCare cua ban" },
  {
    env: "ESMS_BOOKING_TEMPLATE",
    content:
      "VitalCare: {owner} dang ky kham thanh cong cho {patient} ngay {date}, STT {number}, ma {code}. Phi kham: {payment}.",
  },
  {
    env: "ESMS_DESK_TEMPLATE",
    content:
      "VitalCare: {owner} dang ky kham thanh cong cho {patient} ngay {date}, STT {number}, ma {code}. Vui long tra {amount} tai quay le tan.",
  },
];

const URL = "https://rest.esms.vn/MainService.svc/json/SubServicePreviewJson/";

const args = process.argv.slice(2);
const dryRun = args.includes("--dry-run");
const typeAt = args.indexOf("--type");
const smsType = typeAt >= 0 ? args[typeAt + 1] : "8";
const apiKey = process.env.ESMS_API_KEY ?? "";
const secretKey = process.env.ESMS_SECRET_KEY ?? "";
const brandname = process.env.ESMS_BRANDNAME ?? "";

if (!["2", "8"].includes(smsType)) {
  console.error("--type must be 2 (customer care, brandname) or 8 (fixed number)");
  process.exit(1);
}
if (!dryRun && (!apiKey || !secretKey)) {
  console.error("Set ESMS_API_KEY and ESMS_SECRET_KEY in the environment.");
  process.exit(1);
}
if (smsType === "2" && !brandname) {
  console.error("SmsType 2 needs ESMS_BRANDNAME (a brandname already added to the account).");
  process.exit(1);
}

let failed = 0;
for (const { env, content } of TEMPLATES) {
  // Unaccented, one SMS: eSMS counts 160 characters for a non-Unicode text.
  if (content.length > 160) console.warn(`! ${env}: ${content.length} characters before the values are filled in`);
  const body = {
    ApiKey: apiKey,
    SecretKey: secretKey,
    Content: content,
    SmsType: smsType,
    ServiceType: 2,
    IsUnicode: 0,
    ...(brandname ? { Brandname: brandname } : {}),
  };
  if (dryRun) {
    console.log(`[dry-run] ${env}:`, JSON.stringify({ ...body, ApiKey: "***", SecretKey: "***" }));
    continue;
  }
  try {
    const response = await fetch(URL, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    const text = await response.text();
    let answer = {};
    try {
      answer = JSON.parse(text);
    } catch {
      // eSMS does not always label its JSON; anything else is shown as is below.
    }
    const ok = answer.CodeResult === "100";
    if (!ok) failed++;
    console.log(
      `${ok ? "SENT FOR REVIEW" : "REFUSED"} ${env}: CodeResult=${answer.CodeResult ?? response.status}` +
        (answer.ServicePreviewId ? ` id=${answer.ServicePreviewId}` : "") +
        (answer.ErrorMessage ? ` ${answer.ErrorMessage}` : ok ? "" : ` ${text.slice(0, 200)}`),
    );
  } catch (error) {
    failed++;
    console.log(`ERROR ${env}: ${error.message}`);
  }
}
process.exit(failed ? 1 : 0);
