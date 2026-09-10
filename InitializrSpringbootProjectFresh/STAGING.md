# Isolated Cito staging backend

The staging service configuration is recorded in `../ops/environments/cito-environments.json`. Build from this directory with `mvn -B -DskipTests -Dspotless.skip=true clean package --no-transfer-progress`, then start with `sh /app/start.sh`. Railway source archives have no Git metadata, so run Spotless and tests against the Git checkout in CI before packaging. This packaging command does not replace those gates.

Use only the isolated staging database and newly generated secrets. SANDBOX gateway mode is required; do not copy production provider credentials. Startup applies Flyway migrations. Verify /status/health and protected API-reference routes, and record database/billing acceptance before promotion. The staging database is disposable and has no persistent volume. Runtime evidence and canonical service IDs are in the environment contract and Docs/Api/API-REFERENCE-RELEASE.md.
