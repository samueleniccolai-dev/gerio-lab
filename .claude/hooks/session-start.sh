#!/usr/bin/env bash
# SessionStart hook, Claude Code cloud sessions only (also Routines): prepares the container so
# that `./mvnw verify` runs as in CI (SPEC §14). Taken from Gerio. The cloud image lacks three things:
#   1. JDK 25 (it ships 21): installed from Ubuntu's own repositories;
#   2. a running Docker daemon, needed by Quarkus Dev Services (PostgreSQL 17);
#   3. the Chromium build Playwright expects: the image has an older one under
#      /opt/pw-browsers and the Playwright download site is not reachable, so a link
#      with the expected name points at the image's build. CI installs the exact build
#      (scripts/install-browser.sh), and stays the judge of the browser tests.
# Then it downloads the Maven dependencies, so the cached container already has them.
#
# Idempotent, non-interactive, synchronous (the session starts when the tools are
# ready). Every step is best effort: a failure is reported and the next step still runs.
# A local machine is left alone: there the developer installs these once.
set -uo pipefail

if [[ "${CLAUDE_CODE_REMOTE:-}" != "true" ]]; then
  exit 0
fi
cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0

env_file="${CLAUDE_ENV_FILE:-/dev/null}"
jdk=/usr/lib/jvm/java-25-openjdk-amd64
problems=()
warn() { problems+=("$1"); echo "session-start: $1" >&2; }

# 1. JDK 25 (CLAUDE.md: Java 25).
if [[ ! -x "$jdk/bin/java" ]]; then
  { apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
      openjdk-25-jdk-headless; } >/dev/null 2>&1 || warn "JDK 25 not installed (apt-get failed)"
fi
if [[ -x "$jdk/bin/java" ]]; then
  export JAVA_HOME="$jdk" PATH="$jdk/bin:$PATH"
  echo "export JAVA_HOME=$jdk" >>"$env_file"
  echo "export PATH=$jdk/bin:\$PATH" >>"$env_file"
fi

# 2. Docker daemon (Dev Services start PostgreSQL in a container).
docker_up() { docker info >/dev/null 2>&1; }
if ! docker_up && command -v dockerd >/dev/null; then
  # A daemon may be starting (wait for it) or shutting down (wait until it is gone).
  for _ in $(seq 1 30); do
    docker_up && break
    pgrep -x dockerd >/dev/null || break
    sleep 1
  done
  if ! docker_up; then
    # A pid file left by a daemon that is gone would stop the new one at once.
    pgrep -x dockerd >/dev/null || rm -f /var/run/docker.pid
    nohup dockerd >>/tmp/dockerd.log 2>&1 &
    for _ in $(seq 1 60); do
      docker_up && break
      sleep 1
    done
  fi
fi
docker_up || warn "Docker daemon not running (see /tmp/dockerd.log)"

# 3. Maven dependencies, plugins and Quarkus code generation: the cached container keeps
#    them, and step 4 needs Playwright's driver jar.
./mvnw -B -ntp -q -DskipTests test-compile >/tmp/session-start-maven.log 2>&1 ||
  warn "Maven warm-up failed (see /tmp/session-start-maven.log)"

# 4. Chromium for the browser tests.
pw_version="$(sed -n 's:.*<playwright.version>\(.*\)</playwright.version>.*:\1:p' pom.xml)"
driver_jar="$HOME/.m2/repository/com/microsoft/playwright/driver/$pw_version/driver-$pw_version.jar"
if [[ -z "$pw_version" ]]; then
  chromium="not needed (no Playwright in pom.xml yet)"
elif [[ -d /opt/pw-browsers && -f "$driver_jar" ]]; then
  revision="$(unzip -p "$driver_jar" driver/package/browsers.json | python3 -I -c '
import json, sys
print(next(b["revision"] for b in json.load(sys.stdin)["browsers"]
           if b["name"] == "chromium-headless-shell"))')"
  wanted="chromium_headless_shell-$revision/chrome-headless-shell-linux64/chrome-headless-shell"
  if [[ -x "/opt/pw-browsers/$wanted" ]]; then
    browsers=/opt/pw-browsers
    chromium="build $revision"
  else
    have="$(find /opt/pw-browsers -maxdepth 1 -name 'chromium_headless_shell-*' 2>/dev/null | sort -t- -k2 -n | tail -1)"
    exe=""
    for candidate in "$have/chrome-headless-shell-linux64/chrome-headless-shell" \
      "$have/chrome-linux/headless_shell"; do
      [[ -x "$candidate" ]] && exe="$candidate" && break
    done
    if [[ -n "$exe" ]]; then
      browsers="$HOME/.cache/gerio/pw-browsers"
      mkdir -p "$browsers/$(dirname "$wanted")"
      ln -sfn "$exe" "$browsers/$wanted"
      touch "$browsers/chromium_headless_shell-$revision/INSTALLATION_COMPLETE"
      chromium="$(basename "$have") standing in for build $revision"
      warn "Playwright $pw_version wants Chromium build $revision, using the image's $(basename "$have") (CI uses the exact one)"
    else
      warn "no Chromium headless shell under /opt/pw-browsers: browser tests will fail"
    fi
  fi
  [[ -n "${browsers:-}" ]] && echo "export PLAYWRIGHT_BROWSERS_PATH=$browsers" >>"$env_file"
else
  warn "Playwright's driver jar not found: browser tests may fail"
fi

# java -version also prints "Picked up JAVA_TOOL_OPTIONS ...": keep only the version line.
java_version="$("$jdk/bin/java" -version 2>&1 | grep -m1 ' version ' | cut -d'"' -f2)"
docker_version=off
docker_up && docker_version="$(docker info --format '{{.ServerVersion}}' 2>/dev/null)"
echo "session-start: JDK ${java_version:-missing}, Docker $docker_version," \
  "Chromium ${chromium:-missing} for Playwright ${pw_version:-?}; ${#problems[@]} warning(s)"
exit 0
