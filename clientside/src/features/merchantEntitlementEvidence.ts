/** Presentation only: backend authorization and provider activation remain separate. */
export function activeEntitlementCodes(value: unknown, now = Date.now()): string[] {
  if (!Array.isArray(value) || !Number.isFinite(now)) return [];
  const codes = new Set<string>();
  for (const item of value) {
    if (!item || typeof item !== 'object') continue;
    const row = item as Record<string, unknown>;
    if (String(row.status || '').trim().toUpperCase() !== 'ACTIVE') continue;
    const code = row.service_code ?? row.serviceCode;
    if (typeof code !== 'string' || !code.trim()) continue;
    const from = row.starts_at ?? row.startsAt;
    const until = row.ends_at ?? row.endsAt;
    const start = from == null ? Number.NEGATIVE_INFINITY : Date.parse(String(from));
    const end = until == null ? Number.POSITIVE_INFINITY : Date.parse(String(until));
    // Malformed, future and expired windows never become access-granted badges.
    if (Number.isNaN(start) || Number.isNaN(end) || start > now || end <= now) continue;
    codes.add(code.trim().toUpperCase());
  }
  return [...codes];
}
