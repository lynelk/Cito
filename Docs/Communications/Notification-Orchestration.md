# Cito notification orchestration

Brand baseline: Cito 1.2. Affected surfaces: merchant SMS delivery/preferences, platform Communications administration, notification copy, API and operational evidence.

A committed platform event is recorded in `notification_events`. The notification worker resolves its classification, existing merchant notification/channel preferences, recipients and the existing template catalogue. It commits recipient evidence, `communication_messages` and `communication_outbox` together. The existing delivery worker performs smart routing and fallback, and preserves each attempt and provider message ID for tenant-scoped delivery reports. The existing billing usage relay meters SMS segments, using the delivery ID as its billing source reference. Platform administrator messages have zero merchant charge; provider cost remains explicitly estimated or unknown until actual provider evidence exists.

## Catalogue and recipients

`NotificationEventCatalog` is the formal catalogue. It reuses all existing webhook event names and adds account creation/suspension, password/MFA changes, API credentials, production configuration, settlement/reconciliation, ledger imbalance, compliance, provider outage, queue/failure/DLR thresholds, API degradation, balances, scheduled reports and optional campaigns.

- Mandatory: completed financial transactions, account/security changes and critical security/compliance/integrity events. SMS channel opt-outs and marketing STOP do not suppress these notices.
- Operational: explicit merchant event/channel preferences apply. The existing default EMAIL behavior is preserved; the SMS orchestrator does not duplicate existing email delivery. SMS quiet hours currently use UTC when configured through the pre-existing merchant channel API.
- Marketing: explicit SMS event/channel selection and marketing suppression checks apply. STOP is checked at enqueue and again immediately before scheduled dispatch.

Mandatory merchant notices resolve active merchant users. Invalid/missing phone numbers produce visible `NO_VALID_PHONE` or `NO_RECIPIENT` evidence. Groups reference existing active platform administrators; this does not create a second team management system. Assign the Platform Operations, Finance, Security, Compliance and Executive groups in Communications. Group membership is deliberately not inferred from names or broad ADMIN access.

Alert policies serialize deduplication and hourly limits in the database. Critical events also schedule Executive recipients. Recipient escalation levels multiply the policy delay. Acknowledging an event cancels pending escalation deliveries. Critical and mandatory notices bypass quiet hours. Rates/deduplication constrain repeated administrator alerts; individual mandatory merchant notices remain independent of optional preferences.

## Event capture

Existing payment, payout, refund, billing and validation webhook publication writes the event journal even with no webhook subscribers. Synthetic callback probes are excluded. Additive V124 triggers capture committed account/password/MFA, developer API key, encrypted provider credential, completed finance report and closed settlement changes, including legacy application paths. Trigger payloads contain record references only, never credentials, OTPs or financial payloads.

The monitor reads canonical SMS health/outbox/delivery, reconciliation exceptions, trial balances, compliance cases, denied administrative operations and production API request evidence. Default operational thresholds: 100 overdue messages, 20% SMS failures with at least 20 attempts in five minutes, 10 messages requiring DLR overdue by one hour, and three five-minute API windows with at least 20 requests and 10% 5xx. Event catalogue entries alone do not certify that every possible legacy event emitter has been integrated.

## APIs and UI

The existing Communications routing screen includes the searchable catalogue, group membership, escalation, quiet hours, alert policy controls and evidence. All endpoints below require platform ADMIN authorization through the existing security boundary:

| Method | Path under `/api/v2/admin/communication/notifications` | Purpose |
|---|---|---|
| GET | `/catalogue` | Event classification, severity, version and template |
| GET / POST | `/policies` | Read/update enabled flag, deduplication, hourly cap and escalation delay |
| GET | `/groups` | Existing administrator recipients and group settings |
| POST | `/groups/{group}/recipients` | Assign/activate/deactivate an existing administrator and escalation level |
| POST | `/groups/{group}/quiet-hours` | Set an IANA timezone and paired HH:mm window; null pair clears |
| GET | `/evidence?afterId=0` | Event, recipient, template snapshot, logical message, provider attempts, DLR and routing/billing references |
| POST | `/events/{id}/acknowledge` | Audit acknowledgement and cancel pending escalation |

Merchant single/bulk/cancel/reschedule operations remain on their existing signed external and session-scoped workspace APIs. Platform event emission is an internal service operation, not an endpoint allowing a merchant to impersonate system security events. Team, API credentials and Support remain platform capabilities.

## SMSMobilo

Official source checked on 10 September 2026: https://smsmobilo.com/#api. The public example documents `POST https://smsmobilo.com/api/v1/send`, `X-API-Key`, and JSON `to`, optional `from`, and `message`. Acceptance requires `status=ok`, `data.message_id` and a recognized acceptance state. API acceptance is not final delivery.

Use Railway `SMSMOBILO_API_KEY`, or the existing encrypted credential store under provider `SMSMOBILO_SMS`, key `api_key`. No endpoint/auth/field-name guessing is permitted. Missing credentials exclude the adapter from smart routing. Public documentation does not specify the authenticated status-query/callback contract: these capabilities remain disabled until that contract is supplied and verified. A timeout, malformed success or ambiguous server response is UNKNOWN and held for reconciliation; it must not trigger blind resend/fallback. Definite retryable rejection can select another eligible provider.

Do not include HTTP request headers, credentials or unrestricted provider responses in merchant status or delivery traces.

## Release and verification

V122 is unchanged. V124 adds only the notification policy/evidence schema and capture triggers; V123 is reserved for the separate API reference work. The clean MySQL gate executes V1 through V124 and then drives platform event -> policy -> canonical outbox -> fake provider failure -> fake provider fallback -> correlated DLR, duplicate suppression, critical administrator delivery, marketing suppression, future scheduling and unapproved sender rejection.

Run full Maven verify, clean MySQL migration, frontend lint/typecheck/tests/build, dependency/security, API/docs, brand/parity, container and browser gates on the release head. Reconcile current main immediately before merge; promote only the tested release through the required branch workflow. Verify deployed SHA, Flyway, health, APIs and two configured replicas in canonical Railway project `8d361df2-d17e-4d15-984e-435735f22f6c`. Do not accept unrelated staged Railway changes. Provider credentials and code presence are not external provider certification.

The existing float monitor also records `balance.threshold` through the shared orchestrator, using its configured gateway thresholds. SMS alerting does not require the legacy alert email setting. Threshold comparisons use decimal amounts.
