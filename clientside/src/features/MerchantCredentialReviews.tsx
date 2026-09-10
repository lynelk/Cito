import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Badge, Button, Card, Section, Table } from '../ui';
import { request } from '../shared/api/httpClient';

type CredentialReview = { id: number; merchantNumber: string; channelCode: string; environment: string; revision: number; status: string; lastTestStatus?: string; requestedBy: string };
const endpoint = '/api/v2/admin/shared-provider/merchant-credentials';
export default function MerchantCredentialReviews() {
  const client = useQueryClient();
  const [reason, setReason] = useState('');
  const [message, setMessage] = useState('');
  const reviews = useQuery({ queryKey: ['merchant-credential-reviews'], queryFn: () => request<CredentialReview[]>(endpoint) });
  const decision = useMutation({ mutationFn: ({ row, status }: { row: CredentialReview; status: string }) => request(`${endpoint}/${row.id}/decision`, { method: 'POST', body: JSON.stringify({ revision: row.revision, decision: status, reason }) }),
    onSuccess: () => { setMessage('Credential decision recorded.'); setReason(''); client.invalidateQueries({ queryKey: ['merchant-credential-reviews'] }); },
    onError: (error: Error) => setMessage(error.message) });
  return <Section title="Merchant credential reviews">
    <Card>
      <p>Review the current credential revision and verified connection. Approval must come from someone other than the requester.</p>
      <label htmlFor="credential-review-reason">Decision reason</label>
      <textarea style={{ width: '100%', maxWidth: '100%', boxSizing: 'border-box' }} maxLength={1000} id="credential-review-reason" value={reason} onChange={event => setReason(event.target.value)} rows={2} />
      {message ? <p role="status">{message}</p> : null}
      {reviews.error ? <p role="alert">{(reviews.error as Error).message}</p> : null}
      {reviews.isLoading ? <p role="status">Loading credential reviews…</p> : null}
      <Table<CredentialReview> rows={reviews.data ?? []} rowKey={row => row.id} emptyText="No merchant credential configurations." columns={[
        { key: 'merchant', header: 'Merchant', render: row => row.merchantNumber },
        { key: 'scope', header: 'Channel / environment', render: row => `${row.channelCode} / ${row.environment} / revision ${row.revision}` },
        { key: 'status', header: 'State', render: row => <><Badge>{row.status}</Badge><p>{row.lastTestStatus || 'Connection not verified'}</p></> },
        { key: 'requester', header: 'Requested by', render: row => row.requestedBy },
        { key: 'actions', header: 'Decision', render: row => <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>{(row.status === 'SUBMITTED_FOR_APPROVAL' ? ['ACTIVE', 'REJECTED'] : row.status === 'ACTIVE' ? ['DISABLED'] : []).map(status => <Button key={status} disabled={!reason.trim() || decision.isPending} onClick={() => decision.mutate({ row, status })}>{status === 'ACTIVE' ? 'Approve' : status === 'REJECTED' ? 'Reject' : 'Disable'}</Button>)}</div> },
      ]} />
    </Card>
  </Section>;
}
