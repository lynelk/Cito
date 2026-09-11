import React from 'react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import AdminMerchantReadiness from './AdminMerchantReadiness';
vi.mock('./MerchantReadinessPanel', () => ({ default: ({ merchantId }: { merchantId: number }) => <p>Scoped merchant {merchantId}</p> }));
afterEach(cleanup);
describe('administrator readiness selection', () => {
  it('requires deliberate selection and rejects malformed merchant IDs', () => {
    render(<MemoryRouter><AdminMerchantReadiness /></MemoryRouter>);
    expect(screen.getByText('Choose a merchant')).toBeTruthy();
    const input = screen.getByLabelText('Merchant ID');
    fireEvent.change(input, { target: { value: '-1' } });
    expect((screen.getByRole('button', { name: 'Inspect readiness' }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(input, { target: { value: '42' } });
    fireEvent.click(screen.getByRole('button', { name: 'Inspect readiness' }));
    expect(screen.getByText('Scoped merchant 42')).toBeTruthy();
  });
  it('restores a valid scope from a deliberate deep link', () => {
    render(<MemoryRouter initialEntries={['/bo/admin/merchant-readiness?merchantId=77']}><AdminMerchantReadiness /></MemoryRouter>);
    expect(screen.getByText('Scoped merchant 77')).toBeTruthy();
  });
});
