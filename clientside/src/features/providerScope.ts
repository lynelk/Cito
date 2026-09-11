export function filterProviderRows<T extends { channelCode: string }>(rows: readonly T[] | undefined, scope?: string): T[] {
  return (rows ?? []).filter((row) => !scope || row.channelCode === scope);
}
export function filterProviderAdjustments<T extends { sourceAccountId?: number | null; destinationAccountId?: number | null }>(rows: readonly T[] | undefined, accounts: readonly { id: number }[], scope?: string): T[] {
  if (!scope) return [...(rows ?? [])];
  const ids = new Set(accounts.map((account) => account.id));
  return (rows ?? []).filter((row) => {
    const participants = [row.sourceAccountId, row.destinationAccountId].filter((id): id is number => id != null);
    return participants.length > 0 && participants.every((id) => ids.has(id));
  });
}
