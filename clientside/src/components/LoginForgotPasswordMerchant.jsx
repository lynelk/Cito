import React from 'react';
import strings from './locale';
import { Alert, Button, PasswordField, ProgressOverlay, Sheet, TextField } from '../ui';

import { apiFetch } from '../shared/api/httpClient';
import { apiUrl } from '../shared/config';

function initialState(merchantNumber = '') {
  return {
    step: 'request',
    merchant_number: merchantNumber || '',
    email: '',
    verification_code: '',
    new_password: '',
    confirm_password: '',
    loading: false,
    error: '',
    message: '',
  };
}

function ForgotPasswordMerchant({ merchantNumber = '', showForgotPassword, onCloseDialog }) {
  const [state, setState] = React.useState(() => initialState(merchantNumber));

  React.useEffect(() => {
    if (showForgotPassword) {
      setState((prev) => ({ ...prev, merchant_number: merchantNumber || prev.merchant_number || '' }));
    }
  }, [merchantNumber, showForgotPassword]);

  function patch(values) {
    setState((prev) => ({ ...prev, ...values }));
  }

  function close() {
    setState(initialState(merchantNumber));
    onCloseDialog();
  }

  async function requestReset(event) {
    event.preventDefault();
    if (!state.merchant_number || !state.email) {
      patch({ error: strings.merchant_email_required, message: '' });
      return;
    }
    patch({ loading: true, error: '', message: '' });
    try {
      const response = await apiFetch(apiUrl('/auth/requestMerchantUserResetPassword'), {
        method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'include',
        headers: { 'Content-Type': 'application/json' }, redirect: 'follow', referrerPolicy: 'no-referrer',
        body: JSON.stringify({ email: state.email, merchant_number: state.merchant_number }),
      });
      const res = JSON.parse(await response.text());
      if (res.code === '000') {
        patch({ step: 'reset', message: strings.password_reset_request_received, loading: false });
      } else {
        patch({ error: res.message || res.error || strings.unable_request_password_reset, loading: false });
      }
    } catch (error) {
      patch({ error: error.message, loading: false });
    }
  }

  async function resetPassword(event) {
    event.preventDefault();
    if (!state.verification_code || !state.new_password || !state.confirm_password) {
      patch({ error: strings.verification_new_password_required, message: '' });
      return;
    }
    if (state.new_password !== state.confirm_password) {
      patch({ error: strings.password_mismatch, message: '' });
      return;
    }
    patch({ loading: true, error: '', message: '' });
    try {
      const response = await apiFetch(apiUrl('/auth/resetPasswordMerchant'), {
        method: 'POST', mode: 'cors', cache: 'no-cache', credentials: 'include',
        headers: { 'Content-Type': 'application/json' }, redirect: 'follow', referrerPolicy: 'no-referrer',
        body: JSON.stringify({
          email: state.email,
          merchant_number: state.merchant_number,
          verification_code: state.verification_code,
          new_password: state.new_password,
        }),
      });
      const res = JSON.parse(await response.text());
      if (res.code === '000') {
        patch({ message: res.message || strings.password_reset_complete, loading: false });
        close();
      } else {
        patch({ error: res.message || res.error || strings.unable_reset_password, loading: false });
      }
    } catch (error) {
      patch({ error: error.message, loading: false });
    }
  }

  return (
    <>
      <Sheet
        open={Boolean(showForgotPassword)}
        onClose={close}
        title={state.step === 'request' ? strings.reset_merchant_password_title : strings.complete_password_reset_title}
        size="sm"
        footer={<>
          <Button variant="ghost" className="ios-btn--sm" type="button" onClick={close}>{strings.cancel}</Button>
          <Button variant="primary" className="ios-btn--sm" type="submit" form={state.step === 'request' ? 'forgot-merchant-request' : 'forgot-merchant-reset'} loading={state.loading}>{strings.submit}</Button>
        </>}
      >
        {state.error ? <Alert variant="error">{state.error}</Alert> : null}
        {state.message ? <p className="ios-alert" role="status">{state.message}</p> : null}
        {state.step === 'request' ? (
          <form id="forgot-merchant-request" className="ios-form" onSubmit={requestReset} noValidate>
            <p style={{ color: 'var(--ios-text-secondary)', marginTop: 0 }}>{strings.forgot_password_instructions_merchant}</p>
            <TextField id="forgot-merchant-number" label={strings.merchant_number_label} value={state.merchant_number} onValueChange={(value) => patch({ merchant_number: value })} autoComplete="off" />
            <TextField id="forgot-merchant-email" label={strings.email_label} value={state.email} onValueChange={(value) => patch({ email: value })} autoComplete="email" />
          </form>
        ) : (
          <form id="forgot-merchant-reset" className="ios-form" onSubmit={resetPassword} noValidate>
            <TextField id="forgot-merchant-code" label={strings.verification_code_label} value={state.verification_code} onValueChange={(value) => patch({ verification_code: value })} />
            <PasswordField id="forgot-merchant-new-password" label={strings.new_password_label} value={state.new_password} onValueChange={(value) => patch({ new_password: value })} autoComplete="new-password" />
            <PasswordField id="forgot-merchant-confirm-password" label={strings.confirm_password_label} value={state.confirm_password} onValueChange={(value) => patch({ confirm_password: value })} autoComplete="new-password" />
          </form>
        )}
      </Sheet>
      <ProgressOverlay open={state.loading} message={strings.please_wait} />
    </>
  );
}

export default ForgotPasswordMerchant;
