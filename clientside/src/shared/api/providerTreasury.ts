import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from './httpClient';

export interface TreasuryAccount {
  id: number;
  channelCode: string;
  environment: string;
  countryCode: string;
  currencyCode: string;
  accountRole: 'MASTER' | 'COLLECTION' | 'DISBURSEMENT';
  displayName?: string | null;
  parentAccountId?: number | null;
  prefundRequired: 'YES' | 'NO';
  bookBalance: number;
  reservedBalance: number;
  pendingOutgoingBalance: number;
  pendingIncomingBalance: number;
  availableBalance: number;
  providerReportedBalance?: number | null;
  providerBalanceStatus: string;
  providerBalanceAvailable: boolean;
  providerBalanceUpdatedAt?: string | null;
  providerBalanceAgeSeconds?: number | null;
  providerBalanceMessage?: string | null;
  lowFloatThreshold: number;
  lowFloat: boolean;
  reconciliationState: string;
  reconciliationVariance?: number | null;
}

export interface TreasuryAdjustment {
  id: number;
  adjustmentType: 'CREDIT' | 'DEBIT' | 'REBALANCE';
  sourceAccountId?: number | null;
  destinationAccountId?: number | null;
  amount: number;
  reason: string;
  externalReference: string;
  evidenceReference?: string;
  valueDate: string;
  status: string;
  requestedBy: string;
  approvedBy?: string;
}

export interface SharedProviderEntitlement {
  id: number;
  merchantId: number;
  merchantName?: string;
  merchantNumber?: string;
  channelCode: string;
  environment: string;
  countryCode: string;
  currencyCode: string;
  operation: 'COLLECT' | 'PAYOUT';
  status: string;
  perTransactionLimit?: number | null;
  dailyLimit?: number | null;
  usedToday?: number;
  requestedBy?: string;
  approvedBy?: string;
}

export interface ProviderTestMerchant {
  id: number;
  name: string;
  merchantNumber: string;
  status: string;
}

export interface ProviderLiveTestEvent {
  sequenceNumber: number;
  eventType: string;
  status: string;
  message?: string;
  actor: string;
  createdAt: string;
}

export interface ProviderLiveTest {
  id: number;
  testReference: string;
  idempotencyKey: string;
  merchantId: number;
  merchantName: string;
  merchantNumber: string;
  channelCode: string;
  credentialSource: 'PLATFORM_SHARED';
  environment: 'SANDBOX' | 'PRODUCTION';
  countryCode: string;
  currencyCode: string;
  operation: 'COLLECT' | 'PAYOUT';
  amount: number;
  partyMask: string;
  status: string;
  providerReference?: string | null;
  resultMessage?: string | null;
  requestedBy: string;
  approvedBy?: string | null;
  treasuryStatus?: string | null;
  events: ProviderLiveTestEvent[];
}

export interface PlatformCredential {
  revision: number;
  lastTestStatus?: string;
  id: number;
  channelCode: string;
  environment: string;
  countryCode: string;
  currencyCode: string;
  status: string;
  credentials: Record<string, string>;
  updatedBy?: string;
  approvedBy?: string;
}

function post<T>(path: string, body?: unknown): Promise<T> {
  return request<T>(path, {
    method: 'POST',
    ...(body === undefined ? {} : { body: JSON.stringify(body) }),
  });
}

export function useTreasuryAccounts() {
  return useQuery({ queryKey: ['provider-treasury', 'accounts'], queryFn: () => request<TreasuryAccount[]>('/api/v2/admin/provider-treasury/accounts') });
}

export function useTreasuryAdjustments() {
  return useQuery({ queryKey: ['provider-treasury', 'adjustments'], queryFn: () => request<TreasuryAdjustment[]>('/api/v2/admin/provider-treasury/adjustments') });
}

export function useProviderTestMerchants() {
  return useQuery({ queryKey: ['provider-treasury', 'merchants'], queryFn: () => request<ProviderTestMerchant[]>('/api/v2/admin/provider-treasury/merchants') });
}

export function useProviderLiveTests() {
  return useQuery({
    queryKey: ['provider-treasury', 'live-tests'],
    queryFn: () => request<ProviderLiveTest[]>('/api/v2/admin/provider-treasury/live-tests'),
    refetchInterval: (query) => (query.state.data ?? []).some((row) => ['QUEUED', 'PROCESSING', 'PENDING_PROVIDER', 'PENDING_APPROVAL'].includes(row.status)) ? 3000 : false,
  });
}

export function useSharedProviderEntitlements() {
  return useQuery({ queryKey: ['shared-provider', 'entitlements'], queryFn: () => request<SharedProviderEntitlement[]>('/api/v2/admin/shared-provider/entitlements') });
}

export function usePlatformCredentials() {
  return useQuery({ queryKey: ['shared-provider', 'credentials'], queryFn: () => request<PlatformCredential[]>('/api/v2/admin/shared-provider/credentials') });
}

export function useCreateTreasuryAdjustment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => post<TreasuryAdjustment>('/api/v2/admin/provider-treasury/adjustments', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury'] }),
  });
}

export function useApproveTreasuryAdjustment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => post<TreasuryAdjustment>(`/api/v2/admin/provider-treasury/adjustments/${id}/approve`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury'] }),
  });
}

export function useRejectTreasuryAdjustment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => post<TreasuryAdjustment>(`/api/v2/admin/provider-treasury/adjustments/${id}/reject`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury'] }),
  });
}

export function useSetLowFloatThreshold() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, lowFloatThreshold }: { id: number; lowFloatThreshold: number }) => post<TreasuryAccount>(`/api/v2/admin/provider-treasury/accounts/${id}/low-float-threshold`, { lowFloatThreshold }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury', 'accounts'] }),
  });
}

export function useRefreshProviderBalance() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => post<Partial<TreasuryAccount>>(`/api/v2/admin/provider-treasury/accounts/${id}/refresh-provider-balance`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury', 'accounts'] }),
  });
}

export function useReconcileTreasuryAccount() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) => post<TreasuryAccount>(`/api/v2/admin/provider-treasury/accounts/${id}/reconcile`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury'] }),
  });
}

export function useCreateProviderLiveTest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => post<ProviderLiveTest>('/api/v2/admin/provider-treasury/live-tests', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury'] }),
  });
}

export function useApproveProviderLiveTest() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: Record<string, unknown> }) => post<ProviderLiveTest>(`/api/v2/admin/provider-treasury/live-tests/${id}/approve`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['provider-treasury'] }),
  });
}

export function useCreateSharedEntitlement() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => post<SharedProviderEntitlement>('/api/v2/admin/shared-provider/entitlements', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shared-provider', 'entitlements'] }),
  });
}

export function useApproveSharedEntitlement() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => post<SharedProviderEntitlement>(`/api/v2/admin/shared-provider/entitlements/${id}/approve`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shared-provider', 'entitlements'] }),
  });
}

export function useRejectSharedEntitlement() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => post<SharedProviderEntitlement>(`/api/v2/admin/shared-provider/entitlements/${id}/reject`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shared-provider', 'entitlements'] }),
  });
}

export function useSavePlatformCredential() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: Record<string, unknown>) => post<PlatformCredential>('/api/v2/admin/shared-provider/credentials', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shared-provider', 'credentials'] }),
  });
}

export function useApprovePlatformCredential() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => post<PlatformCredential>(`/api/v2/admin/shared-provider/credentials/${id}/approve`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['shared-provider', 'credentials'] }),
  });
}
