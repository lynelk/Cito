import React, { useEffect, useMemo, useState } from 'react';
import Messager from '../../StableMessager';
import { apiFetch } from '../../../shared/api/httpClient';
import { apiUrl } from '../../../shared/config';
import { readStoredUser } from '../../../shared/useAuth';
import './MerchantModuleSms.css';

const TABS = [
  ['overview', 'Overview'],
  ['compose', 'Compose'],
  ['conversations', 'Conversations'],
  ['scheduled', 'Scheduled'],
  ['history', 'History'],
  ['contacts', 'Contacts'],
  ['groups', 'Groups'],
  ['senders', 'Sender IDs'],
  ['templates', 'Templates'],
  ['drafts', 'Drafts'],
];

const GSM_BASIC = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
const GSM_EXTENDED = '^{}\\[~]|€\f';

function analyzeSms(message = '') {
  let gsm = true;
  let units = 0;
  for (const char of message) {
    if (GSM_BASIC.includes(char)) units += 1;
    else if (GSM_EXTENDED.includes(char)) units += 2;
    else { gsm = false; break; }
  }
  if (!gsm) units = message.length;
  const single = gsm ? 160 : 70;
  const joined = gsm ? 153 : 67;
  const segments = units === 0 ? 0 : units <= single ? 1 : Math.ceil(units / joined);
  const limit = segments <= 1 ? single : joined;
  const used = segments <= 1 ? units : units % joined;
  const remaining = units === 0 ? single : used === 0 && segments > 1 ? 0 : limit - used;
  return { encoding: gsm ? 'GSM-7' : 'UCS-2', characters: message.length, units, segments, remaining };
}

function truthyFlag(value) {
  return value === true || value === 'Y' || value === 'YES' || value === 1 || value === '1';
}

function fmtDate(value) {
  if (!value) return '—';
  const d = new Date(value);
  return Number.isNaN(d.getTime()) ? String(value) : d.toLocaleString();
}

function statusTone(status) {
  const value = String(status || '').toUpperCase();
  if (['DELIVERED', 'SENT', 'COMPLETED'].includes(value)) return 'good';
  if (['FAILED', 'REJECTED', 'CANCELLED', 'EXPIRED'].includes(value)) return 'bad';
  if (['SCHEDULED', 'PENDING', 'RECEIVED', 'FALLBACK_PENDING', 'RETRY_PENDING'].includes(value)) return 'warn';
  return 'neutral';
}

async function readJson(path, options = {}) {
  const response = await apiFetch(apiUrl(path), {
    credentials: 'include',
    headers: { 'Content-Type': 'application/json', ...(options.headers || {}) },
    ...options,
  });
  const body = await response.json().catch(() => ({}));
  if (response.status === 401) throw new Error('Your merchant session has expired.');
  if (!response.ok) throw new Error(body.message || body.code || 'The SMS request could not be completed.');
  return body;
}

function Metric({ label, value, hint }) {
  return (
    <div className="sms-metric">
      <span>{label}</span>
      <strong>{value ?? 0}</strong>
      {hint ? <small>{hint}</small> : null}
    </div>
  );
}

function Status({ value }) {
  return <span className={`sms-status sms-status--${statusTone(value)}`}>{value || 'UNKNOWN'}</span>;
}

function Empty({ children }) {
  return <div className="sms-empty">{children}</div>;
}

function SectionHeader({ title, description, action }) {
  return (
    <div className="sms-section-heading">
      <div><h3>{title}</h3>{description ? <p>{description}</p> : null}</div>
      {action || null}
    </div>
  );
}

function MerchantModuleSms({ loader = () => {} }) {
  const [tab, setTab] = useState('overview');
  const [overview, setOverview] = useState({ metrics: {}, recent: [] });
  const [history, setHistory] = useState([]);
  const [contacts, setContacts] = useState([]);
  const [groups, setGroups] = useState([]);
  const [senders, setSenders] = useState([]);
  const [templates, setTemplates] = useState([]);
  const [drafts, setDrafts] = useState([]);
  const [conversations, setConversations] = useState([]);
  const [conversationMessages, setConversationMessages] = useState([]);
  const [selectedConversation, setSelectedConversation] = useState(null);
  const [routePreview, setRoutePreview] = useState(null);
  const [notice, setNotice] = useState('');
  const [error, setError] = useState('');
  const [saving, setSaving] = useState(false);
  const [contactForm, setContactForm] = useState({ displayName: '', phone: '', email: '' });
  const [groupForm, setGroupForm] = useState({ groupName: '', description: '' });
  const [senderForm, setSenderForm] = useState({ senderId: '', senderType: 'ALPHANUMERIC', providerCode: '', countryCode: 'UG', notes: '' });
  const [reply, setReply] = useState('');
  const [compose, setCompose] = useState({
    recipients: '', contactIds: [], groupIds: [], senderId: '', purpose: 'NOTIFICATION',
    content: '', routingStrategy: 'BALANCED', countryCode: 'UG', currencyCode: 'UGX',
    scheduledAt: '', requireDeliveryReceipts: true, requireInbound: false, fallbackEnabled: true,
  });
  const messagerRef = React.useRef(null);
  const analysis = useMemo(() => analyzeSms(compose.content), [compose.content]);
  const approvedSenders = useMemo(() => senders.filter((s) => String(s.approvalStatus || '').toUpperCase() === 'APPROVED'), [senders]);
  const selectedSender = approvedSenders.find((s) => s.senderId === compose.senderId);
  const canTwoWay = selectedSender ? truthyFlag(selectedSender.twoWayCapable) : false;
  const user = readStoredUser('merchant') || {};
  const privileges = new Set((user.privileges || []).map((p) => p.privilege));
  const canSend = privileges.size === 0 || privileges.has('SEND_SMS') || privileges.has('CREATE_BATCH_TX');

  const work = async (fn) => {
    setError(''); setNotice(''); loader('START');
    try { return await fn(); }
    catch (e) { setError(e.message || 'SMS operation failed.'); return null; }
    finally { loader('STOP'); }
  };

  const loadOverview = () => work(async () => setOverview(await readJson('/api/v2/merchant/communication/sms/overview')));
  const loadContacts = () => work(async () => setContacts(await readJson('/api/v2/merchant/communication/sms/contacts')));
  const loadGroups = () => work(async () => setGroups(await readJson('/api/v2/merchant/communication/sms/groups')));
  const loadSenders = () => work(async () => setSenders(await readJson('/api/v2/merchant/communication/sms/sender-identities')));
  const loadTemplates = () => work(async () => setTemplates(await readJson('/api/v2/merchant/communication/sms/templates')));
  const loadDrafts = () => work(async () => setDrafts(await readJson('/api/v2/merchant/communication/sms/drafts')));
  const loadConversations = () => work(async () => setConversations(await readJson('/api/v2/merchant/communication/sms/conversations')));
  const loadHistory = (status = '') => work(async () => setHistory(await readJson(`/api/v2/merchant/communication/sms/history${status ? `?status=${encodeURIComponent(status)}` : ''}`)));

  useEffect(() => {
    loadOverview();
    Promise.all([loadContacts(), loadGroups(), loadSenders(), loadTemplates()]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (tab === 'history') loadHistory();
    if (tab === 'scheduled') loadHistory('SCHEDULED');
    if (tab === 'contacts') loadContacts();
    if (tab === 'groups') loadGroups();
    if (tab === 'senders') loadSenders();
    if (tab === 'templates') loadTemplates();
    if (tab === 'drafts') loadDrafts();
    if (tab === 'conversations') loadConversations();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab]);

  useEffect(() => {
    if (!compose.content) { setRoutePreview(null); return undefined; }
    const timer = setTimeout(async () => {
      try {
        const preview = await readJson('/api/v2/merchant/communication/sms/preview', {
          method: 'POST',
          body: JSON.stringify({
            content: compose.content,
            senderId: compose.senderId || null,
            routingStrategy: compose.routingStrategy,
            countryCode: compose.countryCode,
            currencyCode: compose.currencyCode,
            requireDeliveryReceipts: compose.requireDeliveryReceipts,
            requireInbound: compose.requireInbound,
          }),
        });
        setRoutePreview(preview);
      } catch (e) {
        setRoutePreview({ routable: false, explanation: e.message });
      }
    }, 500);
    return () => clearTimeout(timer);
  }, [compose.content, compose.senderId, compose.routingStrategy, compose.countryCode, compose.currencyCode, compose.requireDeliveryReceipts, compose.requireInbound]);

  const updateCompose = (name, value) => setCompose((current) => ({ ...current, [name]: value }));
  const toggleId = (name, id) => setCompose((current) => {
    const list = new Set(current[name]);
    if (list.has(id)) list.delete(id); else list.add(id);
    return { ...current, [name]: Array.from(list) };
  });

  const sendMessage = async () => {
    if (!canSend) { setError('Your role does not include SMS sending permission.'); return; }
    if (!compose.content.trim()) { setError('Write a message before sending.'); return; }
    if (compose.requireInbound && !canTwoWay) { setError('Choose an approved two-way sender before enabling replies.'); return; }
    const recipients = compose.recipients.split(/[\n,;]+/).map((v) => v.trim()).filter(Boolean);
    if (!recipients.length && !compose.contactIds.length && !compose.groupIds.length) {
      setError('Add a phone number, contact or group.'); return;
    }
    setSaving(true);
    const result = await work(() => readJson('/api/v2/merchant/communication/sms/send', {
      method: 'POST',
      body: JSON.stringify({ ...compose, recipients, scheduledAt: compose.scheduledAt ? new Date(compose.scheduledAt).toISOString() : null }),
    }));
    setSaving(false);
    if (!result) return;
    const suffix = result.suppressed ? ` ${result.suppressed} marketing opt-out recipient(s) were skipped.` : '';
    setNotice(`${result.accepted} SMS message(s) accepted.${suffix}`);
    setCompose((current) => ({ ...current, recipients: '', contactIds: [], groupIds: [], content: '', scheduledAt: '' }));
    setRoutePreview(null);
    loadOverview();
  };

  const saveDraft = async () => {
    if (!compose.content.trim()) { setError('Write a message before saving a draft.'); return; }
    const result = await work(() => readJson('/api/v2/merchant/communication/sms/drafts', {
      method: 'POST',
      body: JSON.stringify({
        title: compose.content.slice(0, 60), recipientMode: 'MIXED',
        recipientPayloadJson: JSON.stringify({ recipients: compose.recipients, contactIds: compose.contactIds, groupIds: compose.groupIds }),
        senderId: compose.senderId, purpose: compose.purpose, messageBody: compose.content,
        routingStrategy: compose.routingStrategy, scheduledAt: compose.scheduledAt || null,
      }),
    }));
    if (result) setNotice('Draft saved.');
  };

  const saveContact = async () => {
    const result = await work(() => readJson('/api/v2/merchant/communication/sms/contacts', {
      method: 'POST', body: JSON.stringify(contactForm),
    }));
    if (result) { setContactForm({ displayName: '', phone: '', email: '' }); setNotice('Contact saved.'); loadContacts(); }
  };

  const saveGroup = async () => {
    const result = await work(() => readJson('/api/v2/merchant/communication/sms/groups', {
      method: 'POST', body: JSON.stringify(groupForm),
    }));
    if (result) { setGroupForm({ groupName: '', description: '' }); setNotice('Group saved.'); loadGroups(); }
  };

  const requestSender = async () => {
    const result = await work(() => readJson('/api/v2/merchant/communication/sms/sender-identities', {
      method: 'POST', body: JSON.stringify(senderForm),
    }));
    if (result) { setSenderForm({ senderId: '', senderType: 'ALPHANUMERIC', providerCode: '', countryCode: 'UG', notes: '' }); setNotice('Sender identity submitted for approval.'); loadSenders(); }
  };

  const openConversation = async (conversation) => {
    setSelectedConversation(conversation); setReply('');
    const rows = await work(() => readJson(`/api/v2/merchant/communication/sms/conversations/${encodeURIComponent(conversation.conversationId)}/messages`));
    if (rows) setConversationMessages([...rows].reverse());
  };

  const replyToConversation = async () => {
    if (!selectedConversation || !reply.trim()) return;
    const result = await work(() => readJson(`/api/v2/merchant/communication/sms/conversations/${encodeURIComponent(selectedConversation.conversationId)}/reply`, {
      method: 'POST', body: JSON.stringify({ content: reply }),
    }));
    if (result) { setReply(''); setNotice('Reply queued.'); openConversation(selectedConversation); }
  };

  const useTemplate = (template) => {
    setCompose((current) => ({ ...current, content: template.body || '' }));
    setTab('compose');
  };

  const loadDraft = (draft) => {
    let recipients = '';
    let contactIds = [];
    let groupIds = [];
    try {
      const payload = typeof draft.recipientPayload === 'string' ? JSON.parse(draft.recipientPayload) : draft.recipientPayload;
      recipients = payload?.recipients || '';
      contactIds = payload?.contactIds || [];
      groupIds = payload?.groupIds || [];
    } catch (_) { /* legacy draft payload is optional */ }
    setCompose((current) => ({
      ...current, recipients, contactIds, groupIds, senderId: draft.senderId || '', purpose: draft.purpose || 'NOTIFICATION',
      content: draft.messageBody || '', routingStrategy: draft.routingStrategy || 'BALANCED', scheduledAt: '',
    }));
    setTab('compose');
  };

  const table = (rows, columns, emptyText = 'Nothing here yet.') => (
    rows.length ? (
      <div className="sms-table-wrap"><table className="sms-table"><thead><tr>{columns.map((c) => <th key={c.key}>{c.label}</th>)}</tr></thead>
        <tbody>{rows.map((row, index) => <tr key={row.id || row.messageReference || row.conversationId || row.draftId || index}>{columns.map((c) => <td key={c.key}>{c.render ? c.render(row) : (row[c.key] ?? '—')}</td>)}</tr>)}</tbody>
      </table></div>
    ) : <Empty>{emptyText}</Empty>
  );

  const renderOverview = () => {
    const m = overview.metrics || {};
    return <>
      <div className="sms-metrics">
        <Metric label="Messages" value={m.totalMessages} hint="All SMS activity" />
        <Metric label="Scheduled" value={m.scheduledMessages} hint="Waiting to send" />
        <Metric label="Contacts" value={m.contacts} hint={`${m.contactGroups || 0} groups`} />
        <Metric label="Unread" value={m.unreadConversations} hint="Two-way conversations" />
        <Metric label="Approved senders" value={m.approvedSenders} hint="Ready to use" />
      </div>
      <div className="sms-card">
        <SectionHeader title="SMS at a glance" description="Cito automatically chooses an eligible route using cost, provider health, capability and your routing policy." action={<button className="sms-btn sms-btn--primary" onClick={() => setTab('compose')}>Compose SMS</button>} />
        <div className="sms-explainer-grid">
          <div><strong>Smart routing</strong><span>Least-cost and health-aware provider selection with automatic retry and fallback.</span></div>
          <div><strong>Two-way ready</strong><span>Inbound messages, replies, delivery receipts and conversation history for supported sender numbers.</span></div>
          <div><strong>API first</strong><span>The same scheduling, sender, routing and segment logic is available through the Cito communications APIs.</span></div>
        </div>
      </div>
      <div className="sms-card"><SectionHeader title="Recent activity" description="Latest outbound SMS messages." />
        {table(overview.recent || [], [
          { key: 'messageReference', label: 'Reference' },
          { key: 'recipient', label: 'Recipient' },
          { key: 'status', label: 'Status', render: (r) => <Status value={r.status} /> },
          { key: 'provider', label: 'Route' },
          { key: 'createdAt', label: 'Created', render: (r) => fmtDate(r.createdAt) },
        ])}
      </div>
    </>;
  };

  const renderCompose = () => <div className="sms-compose-layout">
    <div className="sms-card sms-compose-main">
      <SectionHeader title="Compose SMS" description="Send to one number, contacts, groups or any combination. Duplicates are removed automatically." />
      <label className="sms-field"><span>Direct recipients</span><textarea rows="3" value={compose.recipients} onChange={(e) => updateCompose('recipients', e.target.value)} placeholder="+256700000000, +2567… or one number per line" /></label>
      <div className="sms-picker-row">
        <div><span className="sms-field-label">Contacts</span><div className="sms-chip-list">{contacts.slice(0, 30).map((c) => <button key={c.id} type="button" className={`sms-chip ${compose.contactIds.includes(c.id) ? 'is-selected' : ''}`} onClick={() => toggleId('contactIds', c.id)}>{c.displayName || c.phone}</button>)}</div></div>
        <div><span className="sms-field-label">Groups</span><div className="sms-chip-list">{groups.map((g) => <button key={g.id} type="button" className={`sms-chip ${compose.groupIds.includes(g.id) ? 'is-selected' : ''}`} onClick={() => toggleId('groupIds', g.id)}>{g.groupName} <small>{g.memberCount || 0}</small></button>)}</div></div>
      </div>
      <div className="sms-form-grid">
        <label className="sms-field"><span>Sender ID</span><select value={compose.senderId} onChange={(e) => { updateCompose('senderId', e.target.value); if (!truthyFlag(approvedSenders.find((s) => s.senderId === e.target.value)?.twoWayCapable)) updateCompose('requireInbound', false); }}><option value="">Provider default</option>{approvedSenders.map((s) => <option key={s.id} value={s.senderId}>{s.senderId} · {s.senderType}{truthyFlag(s.twoWayCapable) ? ' · two-way' : ''}</option>)}</select></label>
        <label className="sms-field"><span>Purpose</span><select value={compose.purpose} onChange={(e) => updateCompose('purpose', e.target.value)}><option>NOTIFICATION</option><option>TRANSACTIONAL</option><option>OTP</option><option>SECURITY</option><option>MARKETING</option></select></label>
      </div>
      <label className="sms-field sms-message-field"><span>Message</span><textarea rows="8" value={compose.content} onChange={(e) => updateCompose('content', e.target.value)} placeholder="Write your message…" /><div className="sms-counter"><span>{analysis.encoding}</span><span>{analysis.characters} characters</span><span>{analysis.segments} segment{analysis.segments === 1 ? '' : 's'}</span><span>{analysis.remaining} remaining</span></div></label>
      {analysis.segments > 10 ? <div className="sms-callout sms-callout--warning">This message uses {analysis.segments} SMS segments per recipient. Shortening it can materially reduce cost.</div> : null}
      <div className="sms-form-grid">
        <label className="sms-field"><span>Send</span><input type="datetime-local" value={compose.scheduledAt} onChange={(e) => updateCompose('scheduledAt', e.target.value)} /><small>Leave blank to send now.</small></label>
        <label className="sms-field"><span>Routing strategy</span><select value={compose.routingStrategy} onChange={(e) => updateCompose('routingStrategy', e.target.value)}><option value="BALANCED">Balanced · recommended</option><option value="LOWEST_COST">Lowest cost</option><option value="RELIABILITY_FIRST">Reliability first</option><option value="PRIORITY">Configured priority</option></select></label>
      </div>
      <div className="sms-options">
        <label><input type="checkbox" checked={compose.requireDeliveryReceipts} onChange={(e) => updateCompose('requireDeliveryReceipts', e.target.checked)} /> Delivery receipts</label>
        <label className={!canTwoWay ? 'is-disabled' : ''}><input type="checkbox" disabled={!canTwoWay} checked={compose.requireInbound} onChange={(e) => updateCompose('requireInbound', e.target.checked)} /> Allow replies / two-way SMS</label>
        <label><input type="checkbox" checked={compose.fallbackEnabled} onChange={(e) => updateCompose('fallbackEnabled', e.target.checked)} /> Automatic fallback</label>
      </div>
      <div className="sms-actions"><button className="sms-btn" onClick={saveDraft}>Save draft</button><button className="sms-btn sms-btn--primary" disabled={saving || !canSend} onClick={sendMessage}>{compose.scheduledAt ? 'Schedule SMS' : 'Send SMS'}</button></div>
    </div>
    <aside className="sms-card sms-route-card">
      <SectionHeader title="Route & cost" description="Live estimate. Final routing is re-checked at dispatch." />
      {routePreview ? <>
        <div className="sms-route-result"><span>Recommended route</span><strong>{routePreview.selectedProvider || 'No eligible route'}</strong><Status value={routePreview.routable ? 'AVAILABLE' : 'UNAVAILABLE'} /></div>
        <dl><div><dt>Strategy</dt><dd>{routePreview.routingStrategy || compose.routingStrategy}</dd></div><div><dt>Segments</dt><dd>{routePreview.segments ?? analysis.segments}</dd></div><div><dt>Estimated provider cost</dt><dd>{routePreview.expectedProviderCost == null ? 'Rate not configured' : `${routePreview.currencyCode || compose.currencyCode} ${routePreview.expectedProviderCost}`}</dd></div></dl>
        <p className="sms-route-explanation">{routePreview.explanation}</p>
        {Array.isArray(routePreview.candidates) && routePreview.candidates.length ? <details><summary>Routing candidates</summary>{routePreview.candidates.map((c) => <div key={c.providerCode} className="sms-candidate"><strong>{c.providerCode}</strong><span>{c.eligible ? `Score ${c.score ?? '—'} · ${c.healthState}` : c.exclusionReason}</span></div>)}</details> : null}
      </> : <Empty>Write a message to preview routing and estimated cost.</Empty>}
      <div className="sms-callout"><strong>Provider choice stays automatic.</strong> Cito filters unsupported or unavailable channels first, then optimises within the eligible set.</div>
    </aside>
  </div>;

  const renderConversations = () => <div className="sms-conversation-layout">
    <div className="sms-card sms-thread-list"><SectionHeader title="Conversations" description="Inbound and two-way SMS threads." />
      {conversations.length ? conversations.map((c) => <button key={c.conversationId} className={`sms-thread ${selectedConversation?.conversationId === c.conversationId ? 'is-selected' : ''}`} onClick={() => openConversation(c)}><div><strong>{c.contactName || c.phone}</strong>{Number(c.unreadCount) > 0 ? <b>{c.unreadCount}</b> : null}</div><span>{c.senderId || 'Sender'} · {c.provider || 'Auto route'}</span><small>{fmtDate(c.lastMessageAt)}</small></button>) : <Empty>No two-way SMS conversations yet.</Empty>}
    </div>
    <div className="sms-card sms-thread-pane">{selectedConversation ? <>
      <SectionHeader title={selectedConversation.contactName || selectedConversation.phone} description={`${selectedConversation.phone} · ${selectedConversation.senderId || 'sender'}`} />
      <div className="sms-bubbles">{conversationMessages.map((m) => <div key={m.messageId} className={`sms-bubble sms-bubble--${String(m.direction || '').toLowerCase()}`}><p>{m.body}</p><small>{m.direction} · {fmtDate(m.occurredAt)} · {m.status}</small></div>)}</div>
      <div className="sms-reply"><textarea rows="3" value={reply} onChange={(e) => setReply(e.target.value)} placeholder="Reply…" /><button className="sms-btn sms-btn--primary" onClick={replyToConversation}>Send reply</button></div>
    </> : <Empty>Select a conversation to read and reply.</Empty>}</div>
  </div>;

  const historyColumns = [
    { key: 'messageReference', label: 'Reference' }, { key: 'recipient', label: 'Recipient' }, { key: 'senderId', label: 'Sender' },
    { key: 'status', label: 'Status', render: (r) => <Status value={r.status} /> }, { key: 'provider', label: 'Provider' },
    { key: 'segments', label: 'Segments' }, { key: 'encoding', label: 'Encoding' },
    { key: 'scheduledAt', label: 'Scheduled', render: (r) => fmtDate(r.scheduledAt) }, { key: 'createdAt', label: 'Created', render: (r) => fmtDate(r.createdAt) },
  ];

  const renderContacts = () => <div className="sms-split"><div className="sms-card"><SectionHeader title="Address book" description="Reusable, deduplicated recipient records." />{table(contacts, [
    { key: 'displayName', label: 'Name' }, { key: 'phone', label: 'Phone' }, { key: 'email', label: 'Email' },
    { key: 'actions', label: '', render: (r) => <button className="sms-link" onClick={async () => { await work(() => readJson(`/api/v2/merchant/communication/sms/contacts/${r.id}`, { method: 'DELETE' })); loadContacts(); }}>Remove</button> },
  ])}</div><div className="sms-card sms-form-card"><SectionHeader title="Add contact" /><label className="sms-field"><span>Name</span><input value={contactForm.displayName} onChange={(e) => setContactForm({ ...contactForm, displayName: e.target.value })} /></label><label className="sms-field"><span>Phone</span><input value={contactForm.phone} onChange={(e) => setContactForm({ ...contactForm, phone: e.target.value })} placeholder="+256…" /></label><label className="sms-field"><span>Email · optional</span><input value={contactForm.email} onChange={(e) => setContactForm({ ...contactForm, email: e.target.value })} /></label><button className="sms-btn sms-btn--primary" onClick={saveContact}>Save contact</button></div></div>;

  const renderGroups = () => <div className="sms-split"><div className="sms-card"><SectionHeader title="Contact groups" description="Build reusable audiences without copying phone lists." />{table(groups, [{ key: 'groupName', label: 'Group' }, { key: 'description', label: 'Description' }, { key: 'memberCount', label: 'Members' }, { key: 'updatedAt', label: 'Updated', render: (r) => fmtDate(r.updatedAt) }])}</div><div className="sms-card sms-form-card"><SectionHeader title="Create group" /><label className="sms-field"><span>Group name</span><input value={groupForm.groupName} onChange={(e) => setGroupForm({ ...groupForm, groupName: e.target.value })} /></label><label className="sms-field"><span>Description</span><textarea rows="3" value={groupForm.description} onChange={(e) => setGroupForm({ ...groupForm, description: e.target.value })} /></label><button className="sms-btn sms-btn--primary" onClick={saveGroup}>Save group</button><p className="sms-muted">Add members by selecting contacts and groups from Compose. Group membership management remains available through the SMS workspace API.</p></div></div>;

  const renderSenders = () => <div className="sms-split"><div className="sms-card"><SectionHeader title="Sender identities" description="Only approved identities appear in the composer. Alphanumeric sender IDs are outbound-only unless a provider explicitly supports replies." />{table(senders, [
    { key: 'senderId', label: 'Sender' }, { key: 'senderType', label: 'Type' }, { key: 'approvalStatus', label: 'Approval', render: (r) => <Status value={r.approvalStatus} /> }, { key: 'providerCode', label: 'Provider' }, { key: 'twoWayCapable', label: 'Two-way', render: (r) => truthyFlag(r.twoWayCapable) ? 'Yes' : 'No' },
  ])}</div><div className="sms-card sms-form-card"><SectionHeader title="Request sender" /><label className="sms-field"><span>Sender ID / number</span><input value={senderForm.senderId} onChange={(e) => setSenderForm({ ...senderForm, senderId: e.target.value })} /></label><label className="sms-field"><span>Type</span><select value={senderForm.senderType} onChange={(e) => setSenderForm({ ...senderForm, senderType: e.target.value })}><option>ALPHANUMERIC</option><option>LONG_NUMBER</option><option>SHORT_CODE</option></select></label><label className="sms-field"><span>Provider · optional</span><input value={senderForm.providerCode} onChange={(e) => setSenderForm({ ...senderForm, providerCode: e.target.value })} placeholder="e.g. YO_SMS" /></label><label className="sms-field"><span>Country</span><input value={senderForm.countryCode} onChange={(e) => setSenderForm({ ...senderForm, countryCode: e.target.value.toUpperCase() })} /></label><button className="sms-btn sms-btn--primary" onClick={requestSender}>Submit for approval</button></div></div>;

  const content = (() => {
    if (tab === 'overview') return renderOverview();
    if (tab === 'compose') return renderCompose();
    if (tab === 'conversations') return renderConversations();
    if (tab === 'scheduled') return <div className="sms-card"><SectionHeader title="Scheduled SMS" description="Messages waiting for their send time. Provider selection is re-evaluated at dispatch." />{table(history, historyColumns, 'No scheduled SMS messages.')}</div>;
    if (tab === 'history') return <div className="sms-card"><SectionHeader title="SMS history" description="Searchable delivery evidence across scheduled and immediate sends." />{table(history, historyColumns)}</div>;
    if (tab === 'contacts') return renderContacts();
    if (tab === 'groups') return renderGroups();
    if (tab === 'senders') return renderSenders();
    if (tab === 'templates') return <div className="sms-card"><SectionHeader title="Templates" description="Reusable message content. Variables are rendered by the Communications service when configured." />{table(templates, [{ key: 'templateKey', label: 'Template' }, { key: 'body', label: 'Message' }, { key: 'actions', label: '', render: (r) => <button className="sms-link" onClick={() => useTemplate(r)}>Use template</button> }])}</div>;
    if (tab === 'drafts') return <div className="sms-card"><SectionHeader title="Drafts" description="Resume unfinished messages without rebuilding the audience and routing choices." />{table(drafts, [{ key: 'title', label: 'Draft' }, { key: 'purpose', label: 'Purpose' }, { key: 'senderId', label: 'Sender' }, { key: 'updatedAt', label: 'Updated', render: (r) => fmtDate(r.updatedAt) }, { key: 'actions', label: '', render: (r) => <div className="sms-inline-actions"><button className="sms-link" onClick={() => loadDraft(r)}>Open</button><button className="sms-link sms-link--danger" onClick={async () => { await work(() => readJson(`/api/v2/merchant/communication/sms/drafts/${encodeURIComponent(r.draftId)}`, { method: 'DELETE' })); loadDrafts(); }}>Delete</button></div> }])}</div>;
    return null;
  })();

  return (
    <div className="sms-workspace">
      <div className="sms-workspace-head"><div><span className="sms-eyebrow">Communications · SMS</span><h2>Message customers without managing gateway plumbing</h2><p>Compose, schedule, route, track and reply to SMS from one workspace. Cito chooses an eligible delivery provider automatically unless policy requires otherwise.</p></div><button className="sms-btn sms-btn--primary" onClick={() => setTab('compose')}>New SMS</button></div>
      <nav className="sms-tabs" aria-label="SMS workspace">{TABS.map(([key, label]) => <button key={key} className={tab === key ? 'is-active' : ''} onClick={() => setTab(key)}>{label}{key === 'conversations' && Number(overview.metrics?.unreadConversations) > 0 ? <b>{overview.metrics.unreadConversations}</b> : null}</button>)}</nav>
      {notice ? <div className="sms-notice sms-notice--success">{notice}</div> : null}
      {error ? <div className="sms-notice sms-notice--error">{error}</div> : null}
      {content}
      <Messager ref={messagerRef} />
    </div>
  );
}

export default MerchantModuleSms;
