import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import PaymentRecoveryNotice from './PaymentRecoveryNotice';

afterEach(cleanup);
describe('PaymentRecoveryNotice', () => {
  it.each(['PENDING', 'UNDETERMINED', 'INDETERMINATE'])('discourages resubmission for %s', (status) => {
    render(<PaymentRecoveryNotice status={status} />);
    const notice = screen.getByRole('status');
    expect(notice.textContent).toContain('Keep the original reference');
    expect(notice.textContent).toContain('Do not submit the payment');
    expect(notice.getAttribute('aria-live')).toBe('polite');
  });
  it.each(['SUCCESSFUL', 'FAILED', undefined])('does not mislabel %s as pending', (status) => {
    render(<PaymentRecoveryNotice status={status} />);
    expect(screen.queryByRole('status')).toBeNull();
  });
});
