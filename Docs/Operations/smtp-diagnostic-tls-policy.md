# SMTP diagnostic TLS policy

Date: 11 September 2026. Tracking: #195, PR #198, CodeQL finding 358.

Both SMTP diagnostic scripts now explicitly require TLS 1.2 or later, certificate-chain validation and hostname verification for implicit TLS and STARTTLS. They retain the operating system's trusted CA store and the default client cipher policy. There is no legacy-protocol retry or certificate-verification bypass.

The small factory is intentionally present in each standalone script because the approved diagnostic runner downloads and hashes one immutable script, not a Python package. `ops/diagnostics/test_smtp_tls_policy.py` imports the scripts without executing their main functions and verifies the actual SSLContext settings, independent contexts and every TLS call site. It makes no DNS, database, authentication or email request. The existing Cito Security Containment workflow runs this additional offline gate without weakening the nginx or other release gates.

This is a source hardening change, not a new production diagnostic execution or evidence of inbox delivery. The earlier single diagnostic message remains a separate historical event. Do not send it again solely because this TLS policy changed. The production diagnostic worker remains inert unless a distinct, scoped operational test is authorized. Existing application/provider credentials, TLS settings, payment providers and production data are unchanged.

Brand baseline 1.2: no visual, pricing, API or customer workflow change is introduced by these diagnostic-only files. The parent PR's merchant/admin/web readiness disclosures remain applicable. Passing the offline policy tests does not certify a provider, a live SMTP workflow or a production deployment.
