# Security objective technical review — 11 September 2026

Objective: `OBJ-S-01`, critical vulnerability remediation. Brand baseline: Cito 1.2. This is an automated technical evidence review performed during the owner-authorized release task; it is not a signed SECURITY_OWNER approval or ISO certification.

## Evidence inspected

The previously disabled CI workflow was restored. Its old dependency scan identified affected Spring Framework 7.0.8, Spring Security 7.1.0, Tomcat 11.0.22 and Netty 4.2.15. The reconciled candidate pins vendor fixes 7.0.9, 7.1.1, 11.0.25 and 4.2.16.Final respectively and replaces unused Swagger UI packaging with the API-only Springdoc starter.

Actions run `34558760433` passed full backend verification, the populated MySQL V126-to-V128 upgrade and financial/provider-fake scenarios, frontend typecheck/tests/build, and the existing OWASP CVSS>=9 gate. Test totals were 1,137, with zero failures, errors or skips. The exact tested merge is `b11fc7155a9471f77c17a3f267ac7cc5537e40c6`, tree `fb9de98817205306bb6e68957f24676e28fdcfa3`. Its source bundle and manifest are retained as artifact `10183626423`; backend and dependency reports are artifact `10183627367`. The subsequent Git publication step failed, but the identical published Git object was verified and the feature branch fast-forwarded through the connected GitHub action. This does not turn a failed workflow into a successful workflow; the individual verification steps are the evidence.

The exact-head CI pipeline on the published branch independently repeats the gates. CodeQL security-extended queries are retained; a strict local SARIF gate rejects high/critical findings, while GitHub default setup remains responsible for repository uploads. No vulnerability was suppressed and no severity threshold was lowered.

## Outcome: EVIDENCE_PENDING

Passing the CVSS>=9 dependency gate is not a clean vulnerability report. Lower-severity findings remain in Jackson, Log4j API, MySQL Connector and Netty and require owner triage against resolved artifacts and actual exposure. Final exact-head CodeQL analysis, production deployment identity and production exposure age remain unconfirmed at this review. The candidate's dependency fixes are not claimed to be deployed to production.

Current staging provider-audit evidence reports absent MTN/Airtel sandbox credentials and inactive shared collection/payout channels. No real provider transaction or handset SMS delivery was tested. Repository risks `R-ISO-002` and `R-ISO-004` remain `BLOCKED_PENDING_ACCEPTANCE`, with `productionContinuationAuthorized=false`. No owner acceptance, reviewer approval, provider certification or backup/RPO/RTO assurance is invented or changed by this review.

The review that was due on 10 September was completed as a technical evidence review on 11 September. The next weekly review is scheduled for 18 September 2026. SECURITY_OWNER still owns closure of the vulnerability objective; OPERATIONS_OWNER and CONTINUITY_OWNER still own their separate risk decisions.

No application API, visual identity, monetary behavior or access-control setting is changed by this evidence record. Merchant/admin/public-web release parity remains required.
