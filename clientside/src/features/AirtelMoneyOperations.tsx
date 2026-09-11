import React from 'react';
import { Badge, Card, Section } from '../ui';
import ProviderTreasuryConsole from './ProviderTreasuryConsole';

/** Airtel OpenAPI operations use the canonical treasury controls with a fixed provider scope. */
export default function AirtelMoneyOperations(): React.ReactElement {
  return (
    <div>
      <Section>
        <Card>
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap' }}>
            <h1 style={{ margin: 0 }}>Airtel Money OpenAPI</h1>
            <Badge tone="info">Authenticated status recovery</Badge>
          </div>
          <p>
            Network acceptance is not settlement. Collections and payouts remain pending until an
            authenticated Airtel status lookup returns an explicit final outcome. Missing callbacks,
            rate limits, outages and ambiguous responses do not release funds or trigger another payment.
          </p>
          <p>
            Recovery uses the canonical mobile-money execution journal and its original encrypted account snapshot.
            Pending work survives restarts; fenced claims prevent stale workers from posting outcomes. Conflicting or duplicate references require reconciliation;
            the system does not guess a successful or failed outcome.
          </p>
          <p>
            The controls below are restricted to Airtel OpenAPI. A running application does not prove
            provider authentication or callback registration. Configure approved credentials, verify
            callback registration with Airtel, and complete the required provider tests before launch.
            Live transaction tests can move money and remain subject to MFA and independent approval.
          </p>
        </Card>
      </Section>
      <ProviderTreasuryConsole key="airtel_open_api" channelScope="airtel_open_api" />
    </div>
  );
}
