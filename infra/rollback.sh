#!/usr/bin/env bash
# Puts back the state saved before the last deploy (compose, Caddyfile, image).
# Exit: 0 ok, 1 step failed (also: no previous deploy), 3 missing configuration.
set -euo pipefail
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# shellcheck source=lib.sh
. "$here/lib.sh"

require_vars DEPLOY_HOST DEPLOY_USER DEPLOY_SSH_KEY DEPLOY_KNOWN_HOSTS
setup_ssh
ssh "${SSH_OPTS[@]}" "$SSH_TARGET" /opt/gerio-lab/remote.sh rollback
