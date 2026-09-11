import React from 'react';

/** Pending and uncertain outcomes must not encourage duplicate payment submissions. */
export default function PaymentRecoveryNotice({ status }) {
  const value = typeof status === 'string' ? status.trim().toUpperCase() : '';
  if (!['PENDING', 'UNDETERMINED', 'INDETERMINATE'].includes(value)) return null;
  return (
    <p role="status" aria-live="polite">
      Awaiting provider confirmation. Keep the original reference. Do not submit the payment
      again while confirmation is pending. Check its status or contact support before retrying.
    </p>
  );
}
