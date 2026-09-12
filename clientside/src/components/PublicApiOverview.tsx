import React, { useState } from 'react';
const topics = [
  ['External developer kit', 'Use the consumer-only v2 reference, Postman collection and server-side SDKs. Workspace sessions and administrator APIs are separate; provider activation follows approved onboarding.'],
  ['Payments', 'Collections, payouts, status enquiries and refunds through enabled channels.'],
  ['Authentication', 'Merchant signatures and scoped service credentials protect third-party connections.'],
  ['Webhooks', 'Verified event delivery connects Cito outcomes to your application.'],
  ['Recovery', 'MTN and Airtel recovery checks the original payment reference without resubmitting it. Pending is not settlement; provider certification remains separate.'],
  ['Billing', 'API access rates are set by Cito administrators and may be zero. Service charges are separate.'],
  ['Documentation', 'Sign in to the merchant portal for searchable OpenAPI, schemas, examples and the integration guide.'],
  ['Testing', 'Use an approved sandbox. Production access depends on readiness and service permissions.'],
  ['Service readiness', 'Documented, configured, sandbox verified and production enabled are different states. Confirm the service, merchant and environment before use.'],
  ['Identity', 'GnuGrid supports synchronous verification subject to consent and service controls. Asynchronous callbacks are not supported pending provider certification.'],
  ['Communications', 'Request acceptance and provider acceptance do not prove final SMS or email delivery. Check the available delivery evidence.'],
];
export default function PublicApiOverview(): React.ReactElement {
  const [query, setQuery] = useState('');
  const filtered = topics.filter(topic => topic.join(' ').toLowerCase().includes(query.toLowerCase()));
  return <div className="cito-public-api-topics"><label>Search API topics<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Payments, authentication, webhooks…" /></label>
    <ul>{filtered.map(([title, description]) => <li key={title}><strong>{title}</strong><p>{description}</p></li>)}</ul>{!filtered.length && <p>No matching topics. Open the merchant developer reference for endpoint details.</p>}</div>;
}
