# Cito administrator password recovery

## Purpose

Cito must keep administrator password recovery available even when a legacy deployment is missing optional database settings. Password recovery is an authentication control and must fail safely without exposing reset tokens, credentials, or account data.

## Normal recovery flow

1. The administrator submits the account email to `POST /api/ui/auth/requestResetPassword` (backend route `/auth/requestResetPassword`).
2. Cito validates the account and rate limits the request.
3. Cito issues a short-lived, single-use password-reset token. Only its SHA-256 digest is stored.
4. Cito loads the `email_tmp_pw_reset` template. If the legacy database does not contain that setting, the application installs a safe built-in Cito template at startup without overwriting an operator-customized row.
5. The verification code is sent through the configured SMTP transport.
6. The administrator submits the code and new password through the existing check/reset endpoints.
7. Successful reset clears `must_change_password` and consumes the token.
8. The completion notification uses `email_tmp_on_password_reset_done`, which is also installed only when missing.

## SMTP configuration

Production SMTP credentials must not be committed to the repository. Cito supports the existing database mail settings and the following Railway/runtime variables, with runtime variables taking precedence:

- `CITO_SMTP_HOST`
- `CITO_SMTP_PORT`
- `CITO_SMTP_USERNAME`
- `CITO_SMTP_PASSWORD`
- `CITO_SMTP_FROM`
- `CITO_SMTP_AUTH`
- `CITO_SMTP_STARTTLS`
- `CITO_SMTP_SSL`
- `CITO_SMTP_CONNECTION_TIMEOUT_MS`, `CITO_SMTP_READ_TIMEOUT_MS`, `CITO_SMTP_WRITE_TIMEOUT_MS`

Password recovery and Communications email use one shared SMTP configuration. The equivalent
`mail.smtp.*` database settings remain supported, with nonblank runtime overrides taking precedence.
Mailbox passwords are preserved exactly, including significant whitespace.

| Transport | Port | `CITO_SMTP_SSL` | `CITO_SMTP_STARTTLS` |
|---|---|---|---|
| Implicit TLS | 465 | `true` (automatic on port 465) | `false` (ignored while implicit TLS is enabled) |
| STARTTLS | 587 | `false` | `true` |

Implicit TLS starts the TLS handshake before the SMTP greeting; STARTTLS upgrades an SMTP connection.
Do not switch ports without confirming the mail provider supports the selected transport. Certificate
and hostname verification stay enabled. STARTTLS is required when selected: Cito refuses to send
credentials over an unencrypted fallback connection. Plaintext-only SMTP configuration is rejected.
Connection, read and write timeouts default to 10,000 ms each; configured values must be between
1 and 60,000 ms. Invalid ports, flags, timeouts or incomplete authenticated credentials fail safely.

### Diagnose delivery without sending an email

Temporarily set `CITO_SMTP_CHECK_ON_STARTUP=true` and deploy the released backend. Each replica runs
one connection/authentication probe using the same mailbox configuration. It sends no email and does
not print credentials, reset tokens or message contents. Disable the setting after diagnosis.

- `Cito SMTP probe succeeded` verifies connection and configured authentication only.
- `Cito SMTP probe failed` reports a safe category: `DNS`, `CONNECTION`, `TIMEOUT`, `TLS`,
  `AUTHENTICATION`, `CONFIGURATION` or `SMTP_REJECTED`.
- An unavailable SMTP service is logged without taking payments or the application offline.
- A requested legacy email now logs either `Cito email accepted by SMTP` or
  `Cito email delivery failed`, with a masked recipient and the originating request correlation.

The portal currently acknowledges the reset request before the asynchronous SMTP send completes.
Its success banner and HTTP 200 are not delivery evidence. After a successful probe, request one fresh
code and correlate the send outcome with that request. SMTP acceptance is not proof of inbox delivery;
check provider delivery/bounce evidence and the recipient mailbox before declaring delivery restored.
Communications email continues to return `FAILED` (refundable) on transport/configuration errors and
`SENT` only after SMTP accepts the send. This change does not alter billing, tokens, expiry or API contracts.

The repository's classpath mail configuration contains no production username or password. Any credential previously committed to Git history must be rotated at the mail provider because removing it from the current branch does not erase historical exposure.

## Operational recovery

When email delivery is unavailable, the existing guarded operational recovery mechanism may be used. It requires both `CPAY_ADMIN_RECOVERY_EMAIL` and `CPAY_ADMIN_RECOVERY_TOKEN_SHA256`. The raw token must remain operator-held and must never be placed in source, Railway configuration, or logs.

Operational recovery tokens are short-lived, hashed, single-use tokens marked with `request_ip=ops-admin-recovery`. Browser reset requests invalidate older browser-issued reset tokens but deliberately preserve an active operational recovery token so an operator can complete recovery while SMTP is being repaired.

After recovery, remove or blank the operational recovery variables. Normal password policy, rate limiting, audit controls, and token expiry continue to apply.
