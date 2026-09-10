import React, { useState } from 'react';
const topics = [
  ['Payments', 'Collections, payouts, status enquiries and refunds through enabled channels.'],
  ['Authentication', 'Merchant signatures and scoped service credentials protect third-party connections.'],
  ['Webhooks', 'Verified event delivery connects Cito outcomes to your application.'],
  ['Billing', 'API access rates are set by Cito administrators and may be zero. Service charges are separate.'],
  ['Documentation', 'Sign in to the merchant portal for searchable OpenAPI, schemas, examples and the integration guide.'],
  ['Testing', 'Use an approved sandbox. Production access depends on readiness and service permissions.'],
];
export default function PublicApiOverview(): React.ReactElement {
  const [query, setQuery] = useState('');
  const filtered = topics.filter(topic => topic.join(' ').toLowerCase().includes(query.toLowerCase()));
  return <div className="cito-public-api-topics"><label>Search API topics<input type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Payments, authentication, webhooks…" /></label>
    <ul>{filtered.map(([title, description]) => <li key={title}><strong>{title}</strong><p>{description}</p></li>)}</ul>{!filtered.length && <p>No matching topics. Open the merchant developer reference for endpoint details.</p>}</div>;
}
