# Cito administrator password recovery

## Purpose

Cito must keep administrator password recovery available even when a legacy deployment is missing optional database settings. Password recovery is an authentication control and must fail safely without exposing reset tokens, credentials, or account data.

## Normal recovery flow

1. The administrator submits the account email to `POST /api/admins/account/password-reset/request`.
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

The repository's classpath mail configuration contains no production username or password. Any credential previously committed to Git history must be rotated at the mail provider because removing it from the current branch does not erase historical exposure.

## Operational recovery

When email delivery is unavailable, the existing guarded operational recovery mechanism may be used. It requires both `CPAY_ADMIN_RECOVERY_EMAIL` and `CPAY_ADMIN_RECOVERY_TOKEN_SHA256`. The raw token must remain operator-held and must never be placed in source, Railway configuration, or logs.

Operational recovery tokens are short-lived, hashed, single-use tokens marked with `request_ip=ops-admin-recovery`. Browser reset requests invalidate older browser-issued reset tokens but deliberately preserve an active operational recovery token so an operator can complete recovery while SMTP is being repaired.

After recovery, remove or blank the operational recovery variables. Normal password policy, rate limiting, audit controls, and token expiry continue to apply.
