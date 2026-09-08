# First Administrator Bootstrap

## Purpose

Cito normally requires administrator accounts to be created by an authenticated administrator. A newly recovered or newly provisioned production database can therefore become inaccessible if the `admins` table exists but contains no rows.

`InitialAdminBootstrap` provides a disabled-by-default recovery path for that specific condition. It does not add a public HTTP endpoint and does not create or expose a usable password.

## Activation

Set `CPAY_BOOTSTRAP_ADMIN_EMAIL` on the backend service to the intended administrator email and restart/deploy the backend.

The bootstrap proceeds only when all of the following are true:

- the configured value is a plausible email address;
- the `admins` table contains zero rows;
- the existing `admin_bootstrap_operations` control table is available.

If any administrator record already exists, the bootstrap makes no account or privilege changes.

## Created account

When the empty-database precondition is satisfied, the bootstrap performs one database transaction that:

1. records a bootstrap operation using a SHA-256 digest of the target email;
2. creates one `ACTIVE` administrator with `must_change_password=1`;
3. assigns the current administrator privilege set;
4. records the resulting administrator id and privilege count in the bootstrap operation.

The stored password value is deliberately unusable. No real password or reset code is generated, configured, logged, or committed to source control.

## Establishing the real credential

After the account is verified, the administrator must use Cito's normal **Forgot password** flow to establish the real credential. Password policy, reset-token expiry, audit, rate limiting, and any configured MFA remain enforced by the existing authentication implementation.

## Deactivation

Remove or blank `CPAY_BOOTSTRAP_ADMIN_EMAIL` after the first administrator has been verified. The bootstrap remains fail-closed even if the variable is accidentally left configured because it refuses to create an account whenever any row already exists in `admins`.

## Verification

Operations should record only non-secret evidence:

- backend deployment id and release SHA;
- one active administrator exists at the intended email;
- `must_change_password=1` before the reset flow is completed;
- expected administrator privilege count is present;
- the bootstrap operation is marked complete;
- the bootstrap environment variable has been removed/blanked.

Do not capture the password hash, reset token, raw password, database credentials, or other secrets in deployment evidence.

## Rollback and incident handling

If the bootstrap fails before commit, its transaction must roll back without leaving a partial administrator or privilege set. If an unintended administrator is ever created, treat that as a privileged-access incident: disable the account first, preserve audit evidence, determine how the bootstrap configuration was activated, and follow the approved access-review process rather than silently deleting evidence.
