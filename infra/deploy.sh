#!/usr/bin/env bash
# Usage: deploy.sh ghcr.io/<owner>/<repo>@sha256:<64 hex>
# Exit: 0 ok, 1 step failed, 2 invalid argument, 3 missing configuration.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"

IMAGE_REF="${1:-}"
if ! [[ "$IMAGE_REF" =~ ^ghcr\.io/[A-Za-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]]; then
  echo "invalid image reference (expected ghcr.io/...@sha256:<64 hex>)" >&2
  exit 2
fi
DEMO_ACCOUNT="${DEMO_STRIPE_ACCOUNT_ID:-}"
if [ -n "$DEMO_ACCOUNT" ] && ! [[ "$DEMO_ACCOUNT" =~ ^acct_[A-Za-z0-9]+$ ]]; then
  echo "invalid DEMO_STRIPE_ACCOUNT_ID (expected acct_ followed by letters and digits)" >&2
  exit 2
fi
require_vars DEPLOY_HOST DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_KNOWN_HOSTS LAB_BASE_DOMAIN GHCR_USER GHCR_TOKEN
setup_ssh

remote=/opt/gerio-lab
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" "mkdir -p $remote/incoming"
scp "${SSH_OPTS[@]}" "$here/docker-compose.yml" "$here/Caddyfile" "$here/remote.sh" \
  "$SSH_TARGET:$remote/incoming/"
# Secrets travel only on stdin, one per line: GHCR_TOKEN, STRIPE_SECRET_KEY,
# STRIPE_CONNECT_WEBHOOK_SECRET (empty line = not set). The demo account is validated, not secret.
printf '%s\n%s\n%s\n' "$GHCR_TOKEN" "${STRIPE_SECRET_KEY:-}" "${STRIPE_CONNECT_WEBHOOK_SECRET:-}" |
  ssh "${SSH_OPTS[@]}" "$SSH_TARGET" \
    "chmod 700 $remote/incoming/remote.sh && $remote/incoming/remote.sh deploy '$IMAGE_REF' '$LAB_BASE_DOMAIN' '$GHCR_USER' '$DEMO_ACCOUNT'"
