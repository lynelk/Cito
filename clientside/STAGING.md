# Isolated Cito staging frontend

Build the existing Dockerfile from this directory, start with `/start.sh`, and use `/readyz` as the deployment healthcheck. Set BACKEND_UPSTREAM to the isolated staging backend private hostname on port 8080; never proxy staging to production.

The source branch must match the backend candidate revision. Verify /releasez, the public hero, merchant Developers and admin API workbench before release acceptance. Canonical staging IDs and current provisioning status are recorded in `../ops/environments/cito-environments.json`. Full system documentation remains admin-only; no admin schema belongs in static website assets.
