#!/usr/bin/env bash
# Optional source-only generation; never downloads tooling, changes a server or calls a provider.
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
SPEC="$REPO_ROOT/Docs/Api/consumer/external-openapi.json"
OUT_DIR="${CPAY_CODEGEN_OUT:-$SCRIPT_DIR/generated}"
CLI="${OPENAPI_GENERATOR_CLI:-openapi-generator-cli}"
command -v "$CLI" >/dev/null 2>&1 || { echo 'Install an approved OpenAPI Generator CLI first; see sdk/codegen/README.md.' >&2; exit 1; }
test -f "$SPEC" || { echo 'Generate the filtered consumer contract first.' >&2; exit 1; }
"$CLI" version
"$CLI" generate -i "$SPEC" -g typescript-fetch -o "$OUT_DIR/typescript"
"$CLI" generate -i "$SPEC" -g python -o "$OUT_DIR/python"
printf '%s\n' 'Generated scaffolding only. Add and test the appropriate authentication, environment and idempotency controls before using it.'
