#!/bin/sh
set -eu

: "${PORT:=8080}"
: "${BACKEND_UPSTREAM:=cito-backend.railway.internal:8080}"

# Repair the obsolete private hostname if an older Railway variable still
# overrides the image default. This keeps the deployment fail-safe during the
# service-name migration without preserving the old route as an accepted target.
case "$BACKEND_UPSTREAM" in
    cpay.railway.internal|cpay.railway.internal:*)
        echo "WARN: replacing obsolete CPay private upstream with Cito Backend." >&2
        BACKEND_UPSTREAM="cito-backend.railway.internal:8080"
        ;;
esac

# nginx resolves ordinary proxy_pass hostnames only when its configuration is
# loaded. Railway rotates private instance addresses during rolling deploys, so
# use the container's own trusted DNS resolver for the dynamic upstream below.
: "${DNS_RESOLVER:=$(awk '/^nameserver[[:space:]]+/ { print $2; exit }' /etc/resolv.conf)}"
if [ -z "$DNS_RESOLVER" ]; then
    echo "FATAL: no DNS resolver found in /etc/resolv.conf." >&2
    exit 1
fi
case "$DNS_RESOLVER" in
    *:*) DNS_RESOLVER="[${DNS_RESOLVER}]:53" ;;
    *) DNS_RESOLVER="${DNS_RESOLVER}:53" ;;
esac

export PORT BACKEND_UPSTREAM DNS_RESOLVER

envsubst '${PORT} ${BACKEND_UPSTREAM} ${DNS_RESOLVER}' \
  < /etc/nginx/templates/default.conf.template \
  > /etc/nginx/conf.d/default.conf

nginx -t
exec nginx -g 'daemon off;'
