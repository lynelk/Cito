import { describe, expect, it } from 'vitest';
import { activeEntitlementCodes } from './merchantEntitlementEvidence';
const now = Date.parse('2026-09-11T12:00:00Z');

describe('merchant entitlement evidence', () => {
  it('requires explicit ACTIVE status rather than merely absence from a denylist', () => {
    const rows = ['REQUESTED', 'PENDING', 'DISABLED', 'REVOKED', 'EXPIRED', 'UNKNOWN', '', null]
      .map(status => ({ status, service_code: 'VENDING' }));
    expect(activeEntitlementCodes(rows, now)).toEqual([]);
    expect(activeEntitlementCodes([...rows, { status: 'ACTIVE', service_code: 'CPAY' }], now)).toEqual(['CPAY']);
  });
  it('fails closed on absent or malformed evidence and invalid evaluation time', () => {
    for (const value of [null, undefined, {}, 'ACTIVE', [null, 1, 'CPAY', { status: 'ACTIVE' }]]) {
      expect(activeEntitlementCodes(value, now)).toEqual([]);
    }
    expect(activeEntitlementCodes([{ status: 'ACTIVE', service_code: 'CPAY' }], Number.NaN)).toEqual([]);
  });
  it('honors effective dates including inclusive expiry and rejects invalid windows', () => {
    expect(activeEntitlementCodes([
      { status: 'ACTIVE', service_code: 'FUTURE', starts_at: '2026-09-12T00:00:00Z' },
      { status: 'ACTIVE', service_code: 'EXPIRED', ends_at: '2026-09-11T12:00:00Z' },
      { status: 'ACTIVE', service_code: 'MALFORMED', ends_at: 'not-a-date' },
      { status: 'ACTIVE', serviceCode: 'BILLING', startsAt: '2026-09-10T00:00:00Z', endsAt: '2026-09-12T00:00:00Z' },
    ], now)).toEqual(['BILLING']);
  });
  it('normalizes and deduplicates service codes without inventing provider enablement', () => {
    expect(activeEntitlementCodes([
      { status: ' active ', service_code: ' cpay ' }, { status: 'ACTIVE', serviceCode: 'CPAY' },
      { status: 'ACTIVE', service_code: '  ' },
    ], now)).toEqual(['CPAY']);
  });
});
