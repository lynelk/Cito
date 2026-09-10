import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import ForgotPassword from './LoginForgotPassword';
import ForgotPasswordMerchant from './LoginForgotPasswordMerchant';
import { apiFetch } from '../shared/api/httpClient';

vi.mock('../shared/api/httpClient', () => ({ apiFetch: vi.fn() }));
vi.mock('../shared/config', () => ({ apiUrl: (path) => path }));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe.each([
  ['admin', ForgotPassword, '/auth/requestResetPassword'],
  ['merchant', ForgotPasswordMerchant, '/auth/requestMerchantUserResetPassword'],
])('%s password recovery', (_surface, Component, endpoint) => {
  it('acknowledges the request without claiming successful email delivery', async () => {
    apiFetch.mockResolvedValue(new Response(JSON.stringify({ code: '000', message: 'Operation was successful.' })));
    render(<Component showForgotPassword merchantNumber="TEST-001" onCloseDialog={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Reset request received.');
    expect(screen.queryByText('Operation was successful.')).not.toBeInTheDocument();
    expect(screen.queryByText('Verification code sent.')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Verification Code')).toBeInTheDocument();
    expect(apiFetch).toHaveBeenCalledWith(endpoint, expect.objectContaining({ method: 'POST' }));
  });

  it('keeps a failed request on the request step with its error', async () => {
    apiFetch.mockResolvedValue(new Response(JSON.stringify({ code: '138', message: 'Too many requests. Please try again later.' })));
    render(<Component showForgotPassword merchantNumber="TEST-001" onCloseDialog={vi.fn()} />);
    fireEvent.change(screen.getByLabelText('Email'), { target: { value: 'user@example.test' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many requests.');
    expect(screen.queryByLabelText('Verification Code')).not.toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });
});
