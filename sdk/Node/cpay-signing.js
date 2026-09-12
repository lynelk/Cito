"use strict";
// Cito consumer SDK 2.0. Java-compatible UTF-8 form encoding and UTF-16 ordering.
const crypto = require("node:crypto");
function javaTrim(value) {
  // Java String.trim() with bounded linear scans, not a backtracking suffix regex.
  let start = 0, end = value.length;
  while (start < end && value.charCodeAt(start) <= 0x20) start++;
  while (end > start && value.charCodeAt(end - 1) <= 0x20) end--;
  return value.slice(start, end);
}
function sha256Hex(value) { return crypto.createHash("sha256").update(value, "utf8").digest("hex"); }
function scalar(value) {
  if (typeof value === "string") { encodeURIComponent(value); return value; }
  if (Number.isSafeInteger(value)) return String(value);
  throw new TypeError("Query values must be strings or safe integers; use strings for decimals");
}
function formEncode(value) {
  return encodeURIComponent(value).replace(/[!'()~]/g, c => "%" + c.charCodeAt(0).toString(16).toUpperCase()).replace(/%20/g, "+");
}
function canonicalQuery(query) {
  if (query == null) return "";
  const entries = query instanceof URLSearchParams ? [...query.entries()] : Array.isArray(query) ? query : Object.entries(query);
  const pairs = [];
  for (const [key, value] of entries) {
    if (typeof key !== "string") throw new TypeError("Query names must be strings");
    for (const item of Array.isArray(value) ? value : [value]) if (item != null) pairs.push([scalar(key), scalar(item)]);
  }
  pairs.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : a[1] < b[1] ? -1 : a[1] > b[1] ? 1 : 0);
  return pairs.map(([key, value]) => `${formEncode(key)}=${formEncode(value)}`).join("&");
}
function canonicalString({ method, path, query, timestamp, nonce, body = "" }) {
  return [javaTrim(method).toUpperCase(), javaTrim(path), canonicalQuery(query), javaTrim(timestamp), javaTrim(nonce), sha256Hex(javaTrim(body))].join("\n");
}
function headerValue(value, name) {
  if (typeof value !== "string" || !value || value.length > 256 || /[\x00-\x20\x7f]/.test(value)) throw new Error(`${name} must be a bounded nonempty header value without whitespace`);
  return value;
}
function signRequest({ merchantNumber, privateKeyPem, method, path, query, body = "", timestamp = new Date().toISOString(), nonce = crypto.randomUUID(), idempotencyKey }) {
  headerValue(merchantNumber, "merchantNumber"); headerValue(timestamp, "timestamp"); headerValue(nonce, "nonce");
  if (idempotencyKey != null) headerValue(idempotencyKey, "idempotencyKey");
  const key = crypto.createPrivateKey(privateKeyPem);
  if (key.asymmetricKeyType !== "rsa" || key.asymmetricKeyDetails.modulusLength < 2048) throw new Error("RSA private key of at least 2048 bits required");
  const canonical = canonicalString({ method, path, query, timestamp, nonce, body });
  const signature = crypto.sign("sha256", Buffer.from(canonical, "utf8"), { key, padding: crypto.constants.RSA_PKCS1_PADDING });
  const headers = { "X-CPay-Merchant-Number": merchantNumber, "X-CPay-Signature-Version": "v2", "X-CPay-Timestamp": timestamp, "X-CPay-Nonce": nonce, "X-CPay-Signature": signature.toString("base64") };
  if (idempotencyKey != null) headers["X-CPay-Idempotency-Key"] = idempotencyKey;
  return headers;
}
module.exports = { javaTrim, formEncode, headerValue, sha256Hex, canonicalQuery, canonicalString, signRequest };
