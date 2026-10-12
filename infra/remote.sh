#!/usr/bin/env bash
# Runs ON THE SERVER. Usage:
#   remote.sh deploy <image@sha256:...> <lab-domain> <ghcr-user> [demo-account]
#     stdin, one per line: GHCR token, Stripe secret key, Connect webhook secret (empty = unset)
#   remote.sh rollback
# Everything runs under one flock, rollback included.
set -euo pipefail

ROOT=/opt/gerio-lab
cd "$ROOT"
exec 9> "$ROOT/.deploy.lock"
flock -w 600 9 || { echo "another deploy is running" >&2; exit 1; }

COMPOSE=(docker compose -f "$ROOT/docker-compose.yml" --env-file "$ROOT/.env")

# Rewrites KEY=value in .env atomically.
set_env() {
  local tmp
  tmp=$(mktemp "$ROOT/.env.XXXXXX")
  { grep -v "^$1=" "$ROOT/.env" || true; printf '%s=%s\n' "$1" "$2"; } > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ROOT/.env"
}

start_and_verify() {
  local want="$1" running
  "${COMPOSE[@]}" up -d --remove-orphans --wait --wait-timeout 180
  # Caddyfile is a single-file mount: the container may keep the old inode, so reload explicitly.
  "${COMPOSE[@]}" exec -T caddy caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
  "${COMPOSE[@]}" exec -T caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile
  running=$(docker inspect --format '{{.Image}}' "$("${COMPOSE[@]}" ps -q app)")
  if ! docker image inspect --format '{{join .RepoDigests " "}}' "$running" | grep -qF "${want#*@}"; then
    echo "app is not running the requested digest" >&2
    return 1
  fi
}

deploy() {
  local image="$1" domain="$2" ghcr_user="$3" demo_account="${4:-}"
  local ghcr_token="" stripe_key="" webhook_secret=""
  IFS= read -r ghcr_token || true
  IFS= read -r stripe_key || true
  IFS= read -r webhook_secret || true
  if [ -n "$demo_account" ] && ! [[ "$demo_account" =~ ^acct_[A-Za-z0-9]+$ ]]; then
    echo "invalid DEMO_STRIPE_ACCOUNT_ID" >&2
    exit 2
  fi
  umask 077
  if [ ! -f "$ROOT/.env" ]; then
    if docker volume inspect gerio-lab_pgdata > /dev/null 2>&1; then
      echo ".env is missing but the database volume exists: restore .env by hand (runbook 1)" >&2
      exit 1
    fi
    local tmp
    tmp=$(mktemp "$ROOT/.env.XXXXXX")
    printf 'DB_PASSWORD=%s\n' "$(openssl rand -hex 24)" > "$tmp"
    chmod 600 "$tmp"
    mv "$tmp" "$ROOT/.env"
  fi
  # Save the state in use before overwriting it (only if the last deploy was good).
  if [ -f "$ROOT/last-good" ] && [ -f "$ROOT/docker-compose.yml" ]; then
    mkdir -p "$ROOT/previous"
    cp "$ROOT/docker-compose.yml" "$ROOT/Caddyfile" "$ROOT/previous/"
    grep '^APP_IMAGE=' "$ROOT/.env" > "$ROOT/previous/app-image" || true
  fi
  rm -f "$ROOT/last-good"
  set_env APP_IMAGE "$image"
  set_env PLATFORM_BASE_DOMAIN "$domain"
  set_env LAB_BASE_DOMAIN "$domain"
  [ -z "$stripe_key" ] || set_env STRIPE_SECRET_KEY "$stripe_key"
  [ -z "$webhook_secret" ] || set_env STRIPE_CONNECT_WEBHOOK_SECRET "$webhook_secret"
  [ -z "$demo_account" ] || set_env DEMO_STRIPE_ACCOUNT_ID "$demo_account"
  mv "$ROOT/incoming/docker-compose.yml" "$ROOT/docker-compose.yml"
  cat "$ROOT/incoming/Caddyfile" > "$ROOT/Caddyfile"
  rm -f "$ROOT/incoming/Caddyfile"
  # rollback.sh calls $ROOT/remote.sh: keep an installed copy of this script
  cp "$ROOT/incoming/remote.sh" "$ROOT/remote.sh.new" && chmod 700 "$ROOT/remote.sh.new" && mv "$ROOT/remote.sh.new" "$ROOT/remote.sh"

  trap 'docker logout ghcr.io > /dev/null 2>&1 || true' EXIT
  printf '%s' "$ghcr_token" | docker login ghcr.io -u "$ghcr_user" --password-stdin > /dev/null
  "${COMPOSE[@]}" pull app
  start_and_verify "$image"
  touch "$ROOT/last-good"
}

rollback() {
  if [ ! -f "$ROOT/previous/app-image" ] || [ ! -f "$ROOT/previous/docker-compose.yml" ]; then
    echo "no previous deploy: nothing to roll back to" >&2
    exit 1
  fi
  local line
  line=$(cat "$ROOT/previous/app-image")
  cp "$ROOT/previous/docker-compose.yml" "$ROOT/docker-compose.yml"
  cat "$ROOT/previous/Caddyfile" > "$ROOT/Caddyfile"
  set_env APP_IMAGE "${line#APP_IMAGE=}"
  start_and_verify "${line#APP_IMAGE=}"
}

case "${1:-}" in
  deploy) { [ "$#" -eq 4 ] || [ "$#" -eq 5 ]; } || { echo "usage: remote.sh deploy <image> <domain> <user> [demo-account]" >&2; exit 2; }
          deploy "$2" "$3" "$4" "${5:-}" ;;
  rollback) rollback ;;
  *) echo "usage: remote.sh deploy|rollback" >&2; exit 2 ;;
esac
