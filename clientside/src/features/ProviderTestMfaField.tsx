import { useEffect, useId, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { request } from '../shared/api/httpClient';
import { useAuth } from '../shared/useAuth';

interface MfaPolicy {
  mtnCollectionMfaRequired: boolean;
  suspendedUntil: string;
}

/** A presentation hint only: the server independently rechecks actor, scope and expiry. */
export default function ProviderTestMfaField({ operation, label, value, onChange }: {
  operation: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const id = useId();
  const { user } = useAuth('admin');
  const [now, setNow] = useState(Date.now);
  const policy = useQuery({
    queryKey: ['provider-treasury', 'collection-test-mfa-policy', user.username, user.email],
    queryFn: () => request<MfaPolicy>('/api/v2/admin/provider-treasury/live-tests/mfa-policy'),
    enabled: operation === 'COLLECT',
    retry: false,
    staleTime: 0,
    refetchInterval: 30_000,
  });
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, []);
  const suspended = operation === 'COLLECT' && policy.isSuccess && !policy.isError
    && policy.data?.mtnCollectionMfaRequired === false
    && Date.parse(policy.data.suspendedUntil) > now;
  useEffect(() => {
    // Do not retain an old code or a password-manager value when no code is required.
    if (suspended && value) onChange('');
  }, [suspended, value, onChange]);
  if (suspended) return <div className="mtn-notice" role="status">
    MFA is temporarily suspended for your MTN collection test until {new Date(policy.data!.suspendedUntil).toLocaleString()}.
    {' '}Payment confirmation, merchant approval and transaction limits still apply. Payout MFA is unchanged.
  </div>;
  return <div className="mtn-field"><label htmlFor={id}>{label}</label>
    <input id={id} autoComplete="one-time-code" inputMode="numeric" required type="password"
      value={value} onChange={event => onChange(event.target.value)} />
  </div>;
}
