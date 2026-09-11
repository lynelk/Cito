import React from 'react';
import { useSearchParams } from 'react-router-dom';
import { Button, EmptyState, PageHeader, TextField } from '../ui';
import MerchantReadinessPanel from './MerchantReadinessPanel';

export default function AdminMerchantReadiness(): React.ReactElement {
  const [params, setParams] = useSearchParams();
  const selected = params.get('merchantId') || '';
  const [input, setInput] = React.useState(selected);
  const merchantId = /^[1-9]\d{0,14}$/.test(selected) && Number.isSafeInteger(Number(selected)) ? Number(selected) : null;
  const valid = /^[1-9]\d{0,14}$/.test(input) && Number.isSafeInteger(Number(input));
  return <div className="cito-workspace-stack">
    <PageHeader title="Merchant readiness" subtitle="Inspect the same evidence-derived assessment shown to the merchant. This view does not activate services." />
    <form className="cito-workspace-toolbar" onSubmit={event => { event.preventDefault(); if (valid) setParams({ merchantId: input }); }}>
      <TextField id="readiness-merchant-id" label="Merchant ID" value={input} onValueChange={setInput} />
      <Button type="submit" disabled={!valid}>Inspect readiness</Button>
    </form>
    {merchantId ? <MerchantReadinessPanel key={merchantId} merchantId={merchantId} /> : <EmptyState title="Choose a merchant" description="Enter an existing merchant ID. Backend administrator and tenant permissions remain enforced." />}
  </div>;
}
