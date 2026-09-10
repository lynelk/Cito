import React, { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { request } from '../../shared/api/httpClient';
import { Alert, Button, TextField, Select, WorkspaceDisclosure } from '../../ui';

const base = '/api/v2/admin/communication/notifications';
interface Definition { type: string; classification: string; severity: string; description: string }
interface Policy { event_type: string; enabled_flag: string; dedup_seconds: number; max_per_hour: number; escalation_seconds: number }
interface Group { group_code: string; display_name: string }
interface Recipient { group_code: string; admin_id: number | null; phone_e164: string | null; phone: string; name: string; escalation_level: number; active_flag: string }
interface Evidence { id: number; event_id: string; event_type: string; recipient: string | null; outcome: string | null; communication_status: string | null; provider_code: string | null; provider_message_id: string | null }

export default function ModuleNotificationPolicies(): React.ReactElement {
  const client = useQueryClient();
  const [search, setSearch] = useState('');
  const [group, setGroup] = useState('PLATFORM_OPERATIONS');
  const [admin, setAdmin] = useState('');
  const [phone, setPhone] = useState('');
  const [recipientType, setRecipientType] = useState('phone');
  const [level, setLevel] = useState('0');
  const [quietStart, setQuietStart] = useState('');
  const [quietEnd, setQuietEnd] = useState('');
  const [zone, setZone] = useState('UTC');
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const catalogue = useQuery({ queryKey: ['notification-catalogue'], queryFn: () => request<Definition[]>(`${base}/catalogue`) });
  const policies = useQuery({ queryKey: ['notification-policies'], queryFn: () => request<Policy[]>(`${base}/policies`) });
  const groups = useQuery({ queryKey: ['notification-groups'], queryFn: () => request<{ groups: Group[]; recipients: Recipient[] }>(`${base}/groups`) });
  const evidence = useQuery({ queryKey: ['notification-evidence'], queryFn: () => request<Evidence[]>(`${base}/evidence`) });
  async function save(path: string, body: unknown): Promise<void> {
    setSaving(true); setError(''); setMessage('');
    try {
      await request(`${base}${path}`, { method: 'POST', body: JSON.stringify(body) });
      await Promise.all(['notification-policies', 'notification-groups', 'notification-evidence'].map(key => client.invalidateQueries({ queryKey: [key] })));
      setMessage('Notification settings saved.');
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Unable to save notification settings.'); }
    finally { setSaving(false); }
  }
  const loadError = catalogue.error || policies.error || groups.error || evidence.error;
  return <WorkspaceDisclosure summary="Platform notifications and alerts">
    <p>Mandatory notices bypass optional SMS preferences. Critical alerts bypass quiet hours. Escalation levels schedule additional recipients after the policy delay.</p>
    {loadError ? <Alert variant="error">Notification settings could not be loaded. Refresh to retry.</Alert> : null}
    {error ? <Alert variant="error">{error}</Alert> : null}
    {message ? <Alert variant="success">{message}</Alert> : null}
    <TextField id="notification-search" label="Search event catalogue" value={search} onValueChange={setSearch} />
    <div className="table-responsive"><table className="table"><caption>Notification event catalogue</caption><thead><tr><th>Event</th><th>Classification</th><th>Severity</th><th>Purpose</th></tr></thead><tbody>
      {(catalogue.data ?? []).filter(row => `${row.type} ${row.description}`.toLowerCase().includes(search.toLowerCase())).map(row => <tr key={row.type}><td>{row.type}</td><td>{row.classification}</td><td>{row.severity}</td><td>{row.description}</td></tr>)}
    </tbody></table></div>
    <h3>Recipient and escalation groups</h3>
    <p>Add an international phone number for alert testing or an existing active administrator. Phone recipients receive alerts without platform access. Manage team membership in the platform Team area.</p>
    <Select id="notification-group" label="Alert group" value={group} onValueChange={setGroup} options={(groups.data?.groups ?? []).map(row => ({ value: row.group_code, label: row.display_name }))} />
    <Select id="notification-recipient-type" label="Recipient type" value={recipientType} onValueChange={setRecipientType} options={[{ value: 'phone', label: 'Phone number' }, { value: 'admin', label: 'Administrator' }]} />
    {recipientType === 'phone' ? <TextField id="notification-phone" label="International phone number" value={phone} onValueChange={setPhone} /> : <TextField id="notification-admin" label="Administrator ID" value={admin} onValueChange={setAdmin} />}
    <TextField id="notification-level" label="Escalation level (0–5)" value={level} onValueChange={setLevel} />
    <Button disabled={saving || !(recipientType === 'phone' ? phone : admin)} onClick={() => void save(`/groups/${group}/recipients`, { ...(recipientType === 'phone' ? { phone } : { adminId: Number(admin) }), escalationLevel: Number(level), active: true })}>Save recipient</Button>
    {(groups.data?.recipients ?? []).length === 0 ? <p>No alert recipients configured. Add recipients before enabling production alerts.</p> : null}
    <ul>{(groups.data?.recipients ?? []).map(row => <li key={`${row.group_code}:${row.admin_id ?? row.phone_e164}`}>{row.group_code}: {row.name} ({row.phone}), level {row.escalation_level}, {row.active_flag === 'Y' ? 'Active' : 'Inactive'} <Button disabled={saving} variant="ghost" onClick={() => void save(`/groups/${row.group_code}/recipients`, { ...(row.admin_id === null ? { phone: row.phone_e164 } : { adminId: row.admin_id }), escalationLevel: row.escalation_level, active: row.active_flag !== 'Y' })}>{row.active_flag === 'Y' ? 'Deactivate' : 'Activate'}</Button></li>)}</ul>
    <h3>Group quiet hours</h3>
    <TextField id="notification-quiet-start" label="Quiet start (HH:mm, blank to clear)" value={quietStart} onValueChange={setQuietStart} />
    <TextField id="notification-quiet-end" label="Quiet end (HH:mm)" value={quietEnd} onValueChange={setQuietEnd} />
    <TextField id="notification-zone" label="Timezone" value={zone} onValueChange={setZone} />
    <Button disabled={saving} onClick={() => void save(`/groups/${group}/quiet-hours`, { start: quietStart || null, end: quietEnd || null, timezone: zone })}>Save group quiet hours</Button>
    <h3>Alert policies</h3>
    {(policies.data ?? []).map(policy => <PolicyEditor key={policy.event_type} policy={policy} mandatory={catalogue.data?.find(row => row.type === policy.event_type)?.classification === 'MANDATORY'} saving={saving} save={save} />)}
    <h3>Notification evidence</h3>
    <Button variant="ghost" onClick={() => void evidence.refetch()}>Refresh evidence</Button>
    {(evidence.data ?? []).length === 0 ? <p>No notification evidence recorded.</p> : null}
    <div className="table-responsive"><table className="table"><caption>Event and delivery evidence</caption><thead><tr><th>Event</th><th>Recipient</th><th>Outcome</th><th>Delivery</th><th>Provider reference</th></tr></thead><tbody>{(evidence.data ?? []).map((row, index) => <tr key={`${row.id}:${index}`}><td>{row.event_type}<br />{row.event_id}<br /><Button variant="ghost" disabled={saving} onClick={() => void save(`/events/${row.id}/acknowledge`, {})}>Acknowledge</Button></td><td>{row.recipient || 'Unresolved'}</td><td>{row.outcome || 'Pending policy'}</td><td>{row.communication_status || 'Not queued'}</td><td>{row.provider_code || 'Not selected'} {row.provider_message_id || ''}</td></tr>)}</tbody></table></div>
  </WorkspaceDisclosure>;
}
function PolicyEditor({ policy, mandatory, saving, save }: { policy: Policy; mandatory: boolean; saving: boolean; save: (path: string, body: unknown) => Promise<void> }): React.ReactElement {
  const [dedup, setDedup] = useState(String(policy.dedup_seconds));
  const [limit, setLimit] = useState(String(policy.max_per_hour));
  const [delay, setDelay] = useState(String(policy.escalation_seconds));
  const [enabled, setEnabled] = useState(policy.enabled_flag === 'Y');
  return <details><summary>{policy.event_type} — {enabled ? 'Enabled' : 'Disabled'}</summary>
    <label><input type="checkbox" checked={enabled} disabled={mandatory || saving} onChange={event => setEnabled(event.target.checked)} /> Enabled{mandatory ? ' (mandatory)' : ''}</label>
    <TextField id={`${policy.event_type}-dedup`} label="Deduplication seconds" value={dedup} onValueChange={setDedup} />
    <TextField id={`${policy.event_type}-limit`} label="Maximum alerts per hour" value={limit} onValueChange={setLimit} />
    <TextField id={`${policy.event_type}-delay`} label="Escalation delay in seconds" value={delay} onValueChange={setDelay} />
    <Button disabled={saving} onClick={() => void save('/policies', { eventType: policy.event_type, enabled, dedupSeconds: Number(dedup), maxPerHour: Number(limit), escalationSeconds: Number(delay) })}>Save policy</Button>
  </details>;
}
