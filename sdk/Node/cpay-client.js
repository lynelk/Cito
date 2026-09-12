"use strict";
const { signRequest, canonicalQuery, headerValue } = require("./cpay-signing");
class CPayError extends Error {
  constructor(status, payload, requestId) { super(`Cito returned HTTP ${status}; inspect the response and original operation status`); this.name = "CPayError"; this.status = status; this.payload = payload; this.requestId = requestId; }
}
class CPayTransportError extends Error { constructor(message) { super(message); this.name = "CPayTransportError"; } }
function approvedOrigin(baseUrl) {
  const url = new URL(baseUrl);
  if (url.protocol !== "https:" || !url.hostname || url.username || url.password || !["", "/"].includes(url.pathname) || url.search || url.hash || /[\x00-\x20\x7f\\]/.test(baseUrl) || url.hostname.endsWith(".invalid") || baseUrl.includes("YOUR") || baseUrl.includes("{{")) throw new Error("Use the approved HTTPS origin without path, query, placeholders or credentials");
  return url.origin;
}
class CPayClient {
  constructor({ baseUrl, merchantNumber, privateKeyPem, environment, timeoutMs = 30000, fetchImpl = globalThis.fetch }) {
    this.baseUrl = approvedOrigin(baseUrl); this.merchantNumber = headerValue(merchantNumber, "merchantNumber");
    if (!["SANDBOX", "PRODUCTION"].includes(environment)) throw new Error("environment must explicitly be SANDBOX or PRODUCTION");
    if (!Number.isInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 120000) throw new Error("timeoutMs must be 1..120000");
    if (!privateKeyPem || typeof fetchImpl !== "function") throw new Error("privateKeyPem and fetch are required");
    this.privateKeyPem = privateKeyPem; this.environment = environment; this.timeoutMs = timeoutMs; this.fetch = fetchImpl;
  }
  collect(request, { idempotencyKey } = {}) { return this.payment("collect", request, idempotencyKey); }
  payout(request, { idempotencyKey } = {}) { return this.payment("payout", request, idempotencyKey); }
  payment(operation, request, key) {
    headerValue(key, "idempotencyKey");
    for (const name of ["reference", "currency", "country", "channel"]) if (typeof request[name] !== "string" || !request[name].trim()) throw new Error(`${name} is required`);
    if (typeof request.amount !== "string" || !/^\d+(?:\.\d{1,4})?$/.test(request.amount) || !/[1-9]/.test(request.amount)) throw new Error("amount must be a positive decimal string with at most four decimals");
    return this.post(`/api/v2/native/payments/${operation}`, request, { idempotencyKey: key });
  }
  channels() { return this.get("/api/v2/channels", {}); }
  webhookEvents() { return this.get("/api/v2/webhooks/events", {}); }
  identityCapabilities() { return this.get("/api/v2/identity/capabilities", {}); }
  status(reference) {
    if (typeof reference !== "string" || !/^[A-Za-z0-9_-][A-Za-z0-9._:-]{0,127}$/.test(reference)) throw new Error("Use the original URL-safe reference returned by Cito");
    return this.get(`/api/v2/payments/${encodeURIComponent(reference)}`, {});
  }
  balances() { return this.get("/api/v2/balances", {}); }
  statements({ startDate, endDate, format = "json", limit }) {
    if (!["json", "csv"].includes(format)) throw new Error("format must be json or csv");
    return this.get("/api/v2/statements", { startDate, endDate, format, limit });
  }
  validateAccount(request) { this.requireProductionOnly("Account validation"); return this.post("/api/v2/accounts/validate", request); }
  createPaymentLink(request, { idempotencyKey } = {}) { this.requireProductionOnly("Payment links"); headerValue(idempotencyKey, "idempotencyKey"); return this.post("/api/v2/payment-links", request, { idempotencyKey }); }
  requireProductionOnly(name) { if (this.environment !== "PRODUCTION") throw new Error(`${name} has no verified sandbox selection contract`); }
  payload(request) {
    if (!request || typeof request !== "object" || Array.isArray(request)) throw new Error("request must be a JSON object");
    if (request.merchantNumber !== undefined && request.merchantNumber !== this.merchantNumber) throw new Error("Request merchant mismatch");
    if (request.metadata !== undefined && (!request.metadata || typeof request.metadata !== "object" || Array.isArray(request.metadata) || (request.metadata.environment !== undefined && request.metadata.environment !== this.environment))) throw new Error("Conflicting metadata environment");
    return { ...request, merchantNumber: this.merchantNumber };
  }
  post(path, request, { idempotencyKey } = {}) {
    // Advanced calls must still select a documented merchant-signed route, not BaaS/session/admin APIs.
    if (!path.startsWith("/api/v2/") || /[?#\\]|\/\.\.?\//.test(path) || path.includes("/admin/") || path.includes("/native/billing/") || path.includes("/portal/")) throw new Error("Not a merchant-signed Cito v2 path");
    if (this.environment === "SANDBOX" && !["/api/v2/native/payments/collect", "/api/v2/native/payments/payout"].includes(path)) throw new Error("This write has no verified sandbox selection contract in this client");
    const body = JSON.stringify(this.payload(request), (_key, value) => { if (typeof value === "number" && !Number.isFinite(value)) throw new Error("JSON numbers must be finite"); return value; });
    const headers = { "Content-Type": "application/json", "X-CPay-Environment": this.environment, ...signRequest({ merchantNumber: this.merchantNumber, privateKeyPem: this.privateKeyPem, method: "POST", path, body, idempotencyKey }) };
    return this.send(path, { method: "POST", headers, body });
  }
  get(path, query) {
    if (query.merchantNumber !== undefined && query.merchantNumber !== this.merchantNumber) throw new Error("Query merchant mismatch");
    const parameters = { ...query, merchantNumber: this.merchantNumber };
    const headers = { "X-CPay-Environment": this.environment, ...signRequest({ merchantNumber: this.merchantNumber, privateKeyPem: this.privateKeyPem, method: "GET", path, query: parameters }) };
    return this.send(`${path}?${canonicalQuery(parameters)}`, { method: "GET", headers });
  }
  async send(path, init) {
    let response, text;
    try {
      response = await this.fetch(this.baseUrl + path, { ...init, redirect: "manual", signal: AbortSignal.timeout(this.timeoutMs) });
      text = await response.text();
    } catch (_) { throw new CPayTransportError("Transport failed or timed out; outcome may be unknown. Query the original reference and retain its idempotency key."); }
    let payload = text || null;
    if ((response.headers.get("content-type") || "").toLowerCase().includes("json")) {
      try { payload = JSON.parse(text); } catch (_) { throw new CPayTransportError("Malformed JSON response; do not infer transaction completion"); }
    }
    if (response.status < 200 || response.status >= 300) throw new CPayError(response.status, payload, response.headers.get("x-request-id"));
    return payload;
  }
}
module.exports = { CPayClient, CPayError, CPayTransportError, approvedOrigin };
