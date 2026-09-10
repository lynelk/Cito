import React from 'react';
import { Badge, Card, Section } from '../ui';
import ProviderTreasuryConsole from './ProviderTreasuryConsole';

/**
 * Airtel-specific operational entry point. The underlying Provider Treasury console remains the
 * canonical control surface for credentials, balances, live tests and reconciliation; this page
 * makes the asynchronous Airtel lifecycle and its recovery semantics explicit to operators.
 */
export default function AirtelMoneyOperations(): React.ReactElement {
  return (
    <div>
      <Section>
        <Card>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <h1 style={{ margin: 0 }}>Airtel Money OpenAPI</h1>
            <Badge tone="info">Provider-verified lifecycle</Badge>
          </div>
          <p>
            Collections and payouts remain pending after network acceptance until Airtel returns an
            explicit terminal status. Missing callbacks, rate limits, transport failures and 5xx
            responses do not convert money movement to failed state.
          </p>
          <p>
            Pending merchant and CPay Shared Payments transactions are recovered by authenticated
            status polling. Successful or failed provider evidence is then applied through the
            canonical ledger and treasury reconciliation flow.
          </p>
          <p>
            Use the controls below to manage Airtel credentials, collection and disbursement float,
            synchronize provider balances, run controlled live tests and reconcile provider state.
          </p>
        </Card>
      </Section>
      <ProviderTreasuryConsole />
    </div>
  );
}
