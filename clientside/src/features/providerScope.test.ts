import { describe, expect, it } from 'vitest';
import { filterProviderRows, filterProviderAdjustments } from './providerScope';
describe('Airtel operation scope', () => {
  it('excludes MTN and legacy Airtel from every provider list', () => {
    const rows = [{channelCode:'mtn_momo'}, {channelCode:'airtel_money'}, {channelCode:'airtel_open_api'}];
    expect(filterProviderRows(rows, 'airtel_open_api')).toEqual([{channelCode:'airtel_open_api'}]);
    expect(filterProviderRows(rows)).toHaveLength(3);
  });
  it('excludes adjustments involving another provider or no known account', () => {
    const rows = [{sourceAccountId:1}, {destinationAccountId:2}, {sourceAccountId:1,destinationAccountId:3}, {}];
    expect(filterProviderAdjustments(rows,[{id:1},{id:2}],'airtel_open_api')).toEqual([{sourceAccountId:1},{destinationAccountId:2}]);
  });
  it('handles unloaded data without exposing unscoped records', () => {
    expect(filterProviderRows(undefined,'airtel_open_api')).toEqual([]);
    expect(filterProviderAdjustments([{sourceAccountId:1}],[],'airtel_open_api')).toEqual([]);
  });
});
