#!/usr/bin/env bash
# Black-box check of https://$LAB_BASE_DOMAIN and the demo shop from the runner.
# Exit: 0 ok, 1 check failed, 3 missing configuration.
set -euo pipefail

if [ -z "${LAB_BASE_DOMAIN:-}" ]; then
  echo "missing variable LAB_BASE_DOMAIN" >&2
  exit 3
fi
attempts="${SMOKE_ATTEMPTS:-24}"
delay="${SMOKE_DELAY:-5}"
base="https://${LAB_BASE_DOMAIN}"
demo="https://demo.${LAB_BASE_DOMAIN}"

code() { curl -s -o /dev/null -m 10 -w '%{http_code}' "$@" || true; }

check() {
  local body headers host
  body=$(curl -fsS -m 10 "$base/") || return 1
  case "$body" in *"Negozi locali online"*) ;; *) echo "landing text missing" >&2; return 1 ;; esac
  body=$(curl -fsS -m 10 "$demo/catalogo") || return 1
  case "$body" in *"Fioreria Il Glicine"*) ;; *) echo "demo catalog: shop name missing" >&2; return 1 ;; esac
  body=$(curl -fsS -m 10 "$demo/prodotti/rose-rosse") || return 1
  case "$body" in *"Mazzo di rose rosse"*) ;; *) echo "demo product: name missing" >&2; return 1 ;; esac
  case "$(code -X POST "$base/webhooks/stripe/connect")" in
    400|503) ;;
    *) echo "webhook route unexpected on $base" >&2; return 1 ;;
  esac
  [ "$(code -X POST "$demo/webhooks/stripe/connect")" = "404" ] || { echo "webhook route is open on the demo host" >&2; return 1; }
  for host in "$LAB_BASE_DOMAIN" "demo.${LAB_BASE_DOMAIN}"; do
    headers=$(curl -sI -m 10 "https://${host}/") || return 1
    printf '%s' "$headers" | grep -qi '^strict-transport-security:' || { echo "HSTS missing on $host" >&2; return 1; }
    [ "$(code -I "http://${host}/")" = "308" ] || { echo "http is not 308 on $host" >&2; return 1; }
    [ "$(code "https://${host}/q/health/ready")" = "404" ] || { echo "/q/health/ready is public on $host" >&2; return 1; }
    [ "$(code "https://${host}/internal/tls/allowed")" = "404" ] || { echo "/internal is public on $host" >&2; return 1; }
  done
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
