import React, { useEffect, useState } from 'react';
import { request } from '../shared/api/httpClient';
import { Alert, ErrorState, Section, Skeleton } from '../ui';

type Step = { stepCode: string; stepName?: string; responsibleParty?: string; blocker?: string; guidance?: string };
type Readiness = { readinessAssessment?: { state: string; nextAction: string; scope: string }; blockers?: Step[] };
const labels: Record<string, string> = {
  NOT_CONFIGURED: 'Not configured', CONFIGURED: 'Configured', SANDBOX_VERIFIED: 'Sandbox verified',
  CERTIFICATION_PENDING: 'Certification pending', PRODUCTION_ENABLED: 'Production enabled', DEGRADED: 'Degraded',
};

export default function MerchantReadinessPanel({ merchantId }: { merchantId: number | null }): React.ReactElement {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState<{ loading: boolean; error?: string; data?: Readiness }>({ loading: true });
  useEffect(() => {
    let active = true;
    setState({ loading: true });
    if (!merchantId || !Number.isSafeInteger(merchantId) || merchantId < 1) {
      setState({ loading: false, error: 'Select a merchant account to inspect its readiness.' });
      return () => { active = false; };
    }
    request<Readiness>(`/api/v2/merchants/${merchantId}/onboarding`).then(data => {
      if (active) setState({ loading: false, data });
    }).catch(() => {
      if (active) setState({ loading: false, error: 'Readiness evidence could not be loaded. No ready status is assumed.' });
    });
    return () => { active = false; };
  }, [merchantId, revision]);
  if (state.loading) return <Skeleton label="Loading merchant readiness evidence" />;
  if (state.error) return <ErrorState message={state.error} retry={() => setRevision(value => value + 1)} />;
  const assessment = state.data?.readinessAssessment;
  return <Section title="Merchant readiness evidence">
    <p role="status"><strong>{assessment ? labels[assessment.state] || 'Unknown readiness' : 'Readiness assessment unavailable'}</strong></p>
    <p>{assessment?.nextAction || 'Ask Operations to review the current activation evidence.'}</p>
    <Alert variant="info">Account readiness and entitlements do not certify or enable every provider. Check the specific service, credentials, environment and operating limits before use.</Alert>
    {(state.data?.blockers || []).map(step => <p key={step.stepCode}><strong>{step.stepName || step.stepCode}</strong>: {step.blocker || step.guidance || 'Review required'} · Responsible: {step.responsibleParty || 'Unassigned'}</p>)}
  </Section>;
}
