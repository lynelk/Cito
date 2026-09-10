import React, { useState } from 'react';
import { Alert, Button } from '../../ui';
import { request } from '../../shared/api/httpClient';

export default function CommunicationProviderActivation({ providerCode, providerName, enabled, onSaved }: {
  providerCode: string; providerName: string; enabled: boolean; onSaved: () => Promise<unknown>;
}): React.ReactElement {
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  async function save(): Promise<void> {
    setSaving(true); setError('');
    try {
      await request(`/api/v2/admin/communication/routing/providers/${encodeURIComponent(providerCode)}/activation`, {
        method: 'POST', body: JSON.stringify({ enabled: !enabled }),
      });
      await onSaved();
    } catch (failure) { setError(failure instanceof Error ? failure.message : 'Unable to update provider activation.'); }
    finally { setSaving(false); }
  }
  return <>
    <Button variant="ghost" disabled={saving} onClick={() => void save()}>{enabled ? 'Disable' : 'Enable'} {providerName}</Button>
    {error ? <Alert variant="error">{error}</Alert> : null}
  </>;
}
