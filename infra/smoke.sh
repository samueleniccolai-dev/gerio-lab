#!/usr/bin/env bash
# Black-box check of https://$LAB_BASE_DOMAIN from the runner.
# Exit: 0 ok, 1 check failed, 3 missing configuration.
set -euo pipefail

if [ -z "${LAB_BASE_DOMAIN:-}" ]; then
  echo "missing variable LAB_BASE_DOMAIN" >&2
  exit 3
fi
attempts="${SMOKE_ATTEMPTS:-24}"
delay="${SMOKE_DELAY:-5}"
base="https://${LAB_BASE_DOMAIN}"

code() { curl -s -o /dev/null -m 10 -w '%{http_code}' "$@" || true; }

check() {
  local body headers
  body=$(curl -fsS -m 10 "$base/") || return 1
  case "$body" in *"Negozi locali online"*) ;; *) echo "landing text missing" >&2; return 1 ;; esac
  headers=$(curl -sI -m 10 "$base/") || return 1
  printf '%s' "$headers" | grep -qi '^strict-transport-security:' || { echo "HSTS missing" >&2; return 1; }
  [ "$(code -I "http://${LAB_BASE_DOMAIN}/")" = "308" ] || { echo "http is not 308" >&2; return 1; }
  [ "$(code "$base/q/health/ready")" = "404" ] || { echo "/q/health/ready is public" >&2; return 1; }
  [ "$(code "$base/internal/tls/allowed")" = "404" ] || { echo "/internal is public" >&2; return 1; }
}

for i in $(seq 1 "$attempts"); do
  if check; then
    echo "smoke ok (attempt $i)"
    exit 0
  fi
  [ "$i" -lt "$attempts" ] && sleep "$delay"
done
echo "smoke failed after $attempts attempts" >&2
exit 1
