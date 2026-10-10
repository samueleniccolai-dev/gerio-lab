#!/usr/bin/env bash
# Stop hook: formats Java sources at the end of each Claude turn.
# Stays silent (and does nothing) until the Maven project has the Spotless plugin.
set -euo pipefail

cd "${CLAUDE_PROJECT_DIR:-.}"

[[ -x ./mvnw && -f pom.xml ]] || exit 0
grep -q 'spotless-maven-plugin' pom.xml || exit 0

# Only when Java files changed in the working tree.
if git status --porcelain -- '*.java' | grep -q .; then
  ./mvnw -q spotless:apply >/dev/null 2>&1 || {
    echo "spotless:apply failed: run ./mvnw spotless:apply to see why" >&2
    exit 1
  }
fi
exit 0
