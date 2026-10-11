#!/usr/bin/env bash
# Shared by deploy.sh and rollback.sh (runner side). Sourced, never run.
# Exit codes: 2 invalid argument, 3 missing configuration.

require_vars() {
  local v
  for v in "$@"; do
    if [ -z "${!v:-}" ]; then
      echo "missing variable $v" >&2
      exit 3
    fi
  done
}

# Sets SSH_OPTS and SSH_TARGET; temporary key files are removed on exit.
setup_ssh() {
  umask 077
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT
  printf '%s\n' "$DEPLOY_SSH_KEY" > "$TMP_DIR/key"
  printf '%s\n' "$DEPLOY_KNOWN_HOSTS" > "$TMP_DIR/known_hosts"
  SSH_OPTS=(-i "$TMP_DIR/key" -o IdentitiesOnly=yes -o BatchMode=yes
    -o StrictHostKeyChecking=yes -o UserKnownHostsFile="$TMP_DIR/known_hosts")
  SSH_TARGET="$DEPLOY_USER@$DEPLOY_HOST"
}
