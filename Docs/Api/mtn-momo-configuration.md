# MTN MoMo configuration and treasury accounts

This runbook covers the CPay `mtn_momo` adapter. It follows MTN's official
[API documentation](https://momoapi.mtn.com/api-documentation),
[API collections](https://momoapi.mtn.com/API-collections), and the linked MTN pages for
API-user/key management, callbacks, sandbox use cases, common errors, and best practices.

## Product subscriptions and credentials

Collection and Disbursement are different MTN products. Never place their credentials in one
generic username/key field and never use one product's bearer token for the other product.

| CPay field | MTN meaning | Required | Secret |
| --- | --- | --- | --- |
| `baseUrl` | MTN API origin; paths are derived by CPay | Yes | No |
| `targetEnvironment` | `X-Target-Environment` header | Yes | No |
| `baseCurrency` | Currency submitted to MTN | Yes | No |
| `callbackHost` | Host registered against the API user | Yes | No |
| `callbackUrl` | CPay MTN callback base URL; CPay appends the request UUID | Yes | No |
| `collectionApiUser` | Collection OAuth Basic username | Yes | Yes |
| `collectionApiKey` | Collection OAuth Basic password | Yes | Yes |
| `collectionSubscriptionKey` | Collection primary `Ocp-Apim-Subscription-Key` | Yes | Yes |
| `collectionSecondarySubscriptionKey` | Collection secondary key for controlled rotation | No | Yes |
| `disbursementApiUser` | Disbursement OAuth Basic username | Yes | Yes |
| `disbursementApiKey` | Disbursement OAuth Basic password | Yes | Yes |
| `disbursementSubscriptionKey` | Disbursement primary `Ocp-Apim-Subscription-Key` | Yes | Yes |
| `disbursementSecondarySubscriptionKey` | Disbursement secondary key for controlled rotation | No | Yes |

The MTN Partner Portal issues production credentials. The sandbox Provisioning API creates sandbox
API users and API keys. Do not copy sandbox credentials into the production scope. Keep all secret
fields blank in source control and enter them only through CPay's encrypted credential editor.

## Environment values

| Scope | Base URL | Target environment | Currency |
| --- | --- | --- | --- |
| MTN sandbox | `https://sandbox.momodeveloper.mtn.com` | `sandbox` | `EUR` |
| Uganda production | Issued/confirmed during onboarding | `mtnuganda` | `UGX` |

CPay rejects an MTN sandbox credential stored with a non-EUR currency, a production Uganda
credential whose target is not `mtnuganda`, non-HTTPS endpoints, and callback URLs whose hostname
does not match `callbackHost`.

## Runtime request contract

- Collection token: `POST /collection/token/`; request: `POST /collection/v1_0/requesttopay`.
- Disbursement token: `POST /disbursement/token/`; request: `POST /disbursement/v1_0/transfer`.
- OAuth uses the product API user/API key as Basic authentication and the product subscription key.
- CPay caches each product/credential token separately until shortly before MTN's `expires_in`.
- Every payment receives a new UUID v4 in `X-Reference-Id`; merchant references remain in
  `externalId` and are correlated server-side.
- HTTP 202 means accepted and pending, not successful. Final status comes from the callback or an
  operational status/reconciliation process.
- CPay sends a transaction-specific callback URL by appending the UUID to `callbackUrl`. Configure
  the base as `https://<callbackHost>/api/v2/provider-callbacks/mtn` and allow both POST and PUT at
  the edge. MTN invokes a callback only once, so pending transactions must remain visible for
  status polling and reconciliation.

Never log Authorization headers, subscription keys, API keys, bearer tokens, or full MSISDNs.
Production egress IPs must be whitelisted for Disbursement during MTN onboarding, and MTN callback
source IPs should be restricted at the load balancer/firewall.

## Administrator authentication

The CPay platform credential editor at `/bo/admin/mtn-momo?environment=PRODUCTION` uses the signed-in human
administrator's portal session. Its provider-treasury and shared-provider API calls require
`ROLE_ADMIN`, matching the rest of Cito's `/api/v2/admin/**` surface. Do not enter a portal password,
MTN API key, or subscription key into a browser-native HTTP Basic dialog. An expired or missing
portal session returns Cito's JSON `401` response without a `WWW-Authenticate` challenge so the UI
can send the operator back through the normal administrator login.

The separate `ADMIN_API` Basic identity remains available for approved machine integrations. It is
not a substitute for a human operator identity: credential edits and approvals retain their actor
identity, audit trail, and maker-checker separation.

## Default account topology

Each provider/country/currency/environment scope has one non-posting `MASTER` control account and
two operational sub-accounts:

| Account role | Used for | Initial balance | Prefund |
| --- | --- | --- | --- |
| `MASTER` | Scope grouping and control visibility | Zero | No |
| `COLLECTION` | Confirmed collection inflows and pending receivables | Zero | No |
| `DISBURSEMENT` | Payout reservations, pending outflows, and settlement | Zero | Required |

The migration creates these sub-accounts for the existing provider scopes and adds an MTN sandbox
UG/EUR scope. It does not copy a master balance into children or fabricate funds. Before enabling
shared-provider payouts, post a maker-checker `CREDIT` adjustment to the applicable
`DISBURSEMENT` sub-account using the bank/provider funding reference and evidence. Reconcile each
operational sub-account independently to the matching provider product statement.

### Core application operational accounts

The application also retains four merchant-shaped internal accounts because established posting,
reversal, reporting, and SMS workflows resolve these settings to `Merchant` records. Migration
V113 creates them without users, keys, API permissions, or opening funds:

| Setting | Default account | Purpose |
| --- | --- | --- |
| `float_stock_account` | `CITO-FLOAT-STOCK` | Compatibility float/stock postings |
| `revenue_account` | `CITO-GATEWAY-REVENUE` | Gateway fee revenue |
| `suspense_account` | `CITO-GATEWAY-SUSPENSE` | Pending and ambiguous postings |
| `sms_revenue_account` | `CITO-SMS-REVENUE` | SMS revenue |

These are not substitutes for the provider product sub-accounts. MTN provider money and
reconciliation remain separated under the MTN `COLLECTION` and `DISBURSEMENT` treasury accounts.
All seeded balances are zero. Fund only through an approved, evidenced posting after the matching
provider wallet is active; never update balance columns directly.

## Activation checklist

1. Subscribe to both Collection and Disbursement in the correct MTN environment.
2. Obtain each product's API user, API key, primary key, and secondary key.
3. Register the CPay callback host and configure the exact callback base URL above.
4. Save the encrypted credential set, verify both products, then have a different administrator approve the tested revision.
5. Restrict production callback source IPs and whitelist CPay's production egress IP for payouts.
6. Run a real MTN sandbox collection and payout; retain request UUID, HTTP evidence, callback, and
   final status evidence.
7. Reconcile the Collection and Disbursement sub-accounts separately.
8. Credit the production Disbursement sub-account only after MTN confirms the prefunded wallet.


## Portal setup and acceptance journey (12 September 2026)

Open **Settings → MTN MoMo → Configure and verify MTN MoMo**, or **Operations → MTN MoMo**.
The focused workspace stays inside the admin shell. The Settings link preserves the selected
provider environment; legacy MTN treasury links retain their query and fragment when redirected.
The workspace's explicit MTN environment applies to its configuration and payment requests.

1. **Connection:** choose Sandbox (UG/EUR) or Production (UG/UGX). Enter each product's API user,
   API key and subscription key in its own panel. Keep the API origin, target and currency derived
   by Cito. Register the callback host with MTN for both products and use the callback base URL
   described above. Save credentials; saving does not activate them. For an existing connection,
   blank secrets keep their stored values. Never paste masked values. A concurrent edit rejects the
   older revision rather than overwriting it.
2. **Verify both products:** the server performs only OAuth requests, separately for COLLECT and
   PAYOUT, even when one fails. Each result includes a safe status and next action. HTTP 401/403
   indicates a product authentication problem; a successful token response is not payment or
   settlement evidence. Both products must pass for the current revision before independent
   approval. Saving changes clears prior verification and approval. Probe details are returned for
   the current request; the overall result and timestamp persist.
3. **Merchant access:** choose an active merchant and request COLLECT and PAYOUT access separately
   for the same provider/environment/country/currency. Set appropriate limits and have a different
   operator approve each request. Merchant-owned connections remain independent and take precedence
   in normal routing unless PLATFORM_SHARED is explicitly requested.
4. **Balances:** inspect Cito available/reserved float separately from MTN's reported wallet balance.
   Unavailable provider balances are not zero. Fund and reconcile the disbursement account through
   the existing evidenced maker-checker treasury workflow, including sandbox test float where the
   configured policy requires it. The portal never invents opening funds.
5. **Payment tests:** select the merchant and operation, then explicitly enter an amount and wallet
   number. MTN sandbox requests use EUR. Follow the official [sandbox scenarios](https://momoapi.mtn.com/api-documentation/testing)
   for success, failure and pending outcomes. Production requires MFA and explicit confirmation of
   real-money movement. Payout tests also require independent approval; sandbox approvals do not
   require production MFA. Existing merchant API permissions, environment restrictions, limits,
   funding checks and provider credentials remain authoritative.
6. Follow the request reference, provider UUID, event timeline and treasury state to the final
   outcome. A failed response is shown as failed; HTTP 202 remains pending. Transport errors retain
   the original request key and financial details for an identical safe retry. Do not create a new
   request to chase an uncertain outcome. Callbacks and scheduled status queries use the canonical
   recovery path and cannot manufacture successful finality.

For regular merchant payments, the portal and v2 collection/payout APIs continue to use the same
adapter, scoped credentials, reservations, idempotency and recovery as these tests. Shared MTN
access is not enabled globally by saving general Settings. Retain sandbox evidence for both products,
then obtain a separately authorised production acceptance case with merchant, amount and wallet.
CI fixtures prove software behavior; only actual provider results prove live MTN acceptance.
