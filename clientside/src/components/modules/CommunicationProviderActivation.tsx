import React, { useState } from 'react';
import { Alert, Button, TextField } from '../../ui';
import { request } from '../../shared/api/httpClient';

type TestResult = {
  status?: string;
  normalizedCode?: string;
  providerAccepted?: boolean;
  deliveryConfirmed?: boolean;
};

export default function CommunicationProviderActivation({ providerCode, providerName, enabled, onSaved }: {
  providerCode: string; providerName: string; enabled: boolean; onSaved: () => Promise<unknown>;
}): React.ReactElement {
  const [saving, setSaving] = useState(false);
  const [testing, setTesting] = useState(false);
  const [recipient, setRecipient] = useState('');
  const [senderId, setSenderId] = useState('');
  const [error, setError] = useState('');
  const [testResult, setTestResult] = useState<TestResult | null>(null);

  async function save(): Promise<void> {
    setSaving(true); setError(''); setTestResult(null);
    try {
      await request(`/api/v2/admin/communication/routing/providers/${encodeURIComponent(providerCode)}/activation`, {
        method: 'POST', body: JSON.stringify({ enabled: !enabled }),
      });
      await onSaved();
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Unable to update provider activation.'); }
    finally { setSaving(false); }
  }

  async function sendTest(): Promise<void> {
    const normalized = recipient.trim();
    if (!/^\+?[0-9]{7,15}$/.test(normalized)) {
      setError('Enter a valid recipient number before sending the provider test.');
      return;
    }
    setTesting(true); setError(''); setTestResult(null);
    try {
      const result = await request<TestResult>(`/api/v2/admin/communication/routing/providers/${encodeURIComponent(providerCode)}/test-sms`, {
        method: 'POST',
        body: JSON.stringify({ recipient: normalized, senderId: senderId.trim() || null }),
      });
      setTestResult(result);
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : 'Unable to send the SMS provider test.');
    } finally {
      setTesting(false);
    }
  }

  return <div className="cito-form-stack" style={{ minWidth: 260 }}>
    <Button variant="ghost" disabled={saving || testing} onClick={() => void save()}>{enabled ? 'Disable' : 'Enable'} {providerName}</Button>
    {enabled ? <>
      <TextField
        id={`sms-test-recipient-${providerCode}`}
        label="Test recipient"
        value={recipient}
        onValueChange={setRecipient}
        placeholder="2567…"
      />
      <TextField
        id={`sms-test-sender-${providerCode}`}
        label="Sender ID (optional)"
        value={senderId}
        onValueChange={setSenderId}
        placeholder="Cito"
      />
      <Button variant="primary" disabled={saving || testing} loading={testing} loadingLabel="Sending test…" onClick={() => void sendTest()}>
        Send test SMS
      </Button>
    </> : null}
    {testResult ? <Alert variant={testResult.providerAccepted ? 'success' : 'error'}>
      Provider status: <strong>{testResult.status || 'UNKNOWN'}</strong>. {testResult.providerAccepted
        ? (testResult.deliveryConfirmed ? 'Delivery confirmed.' : 'Provider accepted the test; handset delivery is not yet confirmed.')
        : `Provider did not accept the test${testResult.normalizedCode ? ` (${testResult.normalizedCode})` : ''}.`}
    </Alert> : null}
    {error ? <Alert variant="error">{error}</Alert> : null}
  </div>;
}
