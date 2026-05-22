#!/bin/bash
# Usage: ./deploy.sh           — build, upload JAR, then restart service (requires ALWAYSDATA_API_KEY)
#        ./deploy.sh config    — scp config.properties to the server, then restart (if API key available)
#        ./deploy.sh restart  — only restart via AlwaysData API (no build/scp)
# API key: use one of
#   • a file `deploy.secrets.sh` in this directory (see below; not committed to git)
#   • environment: ALWAYSDATA_API_KEY=... ./deploy.sh restart
set -e

cd "$(dirname "$0")"

# Optional local file (for ./deploy.sh restart and deploy): create deploy.secrets.sh with:
#   export ALWAYSDATA_API_KEY='your-token'
# (admin → https://admin.alwaysdata.com → API tokens)
if [ -f deploy.secrets.sh ]; then
  # shellcheck disable=SC1091
  . deploy.secrets.sh
fi

ALWAYSDATA_SERVICE_ID="${ALWAYSDATA_SERVICE_ID:-23002}"
ALWAYSDATA_ACCOUNT="${ALWAYSDATA_ACCOUNT:-duhmatias}"
# Same host/path as the JAR upload (working directory for the app is typically www/).
SCP_HOST="${SCP_HOST:-duhmatias@ssh-duhmatias.alwaysdata.net}"
SCP_WWW="${SCP_WWW:-www}"
# Stable remote jar name: the local build produces target/smn-weather-mail-<version>.<timestamp>.jar but we always
# upload it to the same destination filename so the AlwaysData service start command never needs to change after a
# MAJOR/MINOR/PATCH bump (see .cursor/rules/versioning.mdc). Override with REMOTE_JAR_NAME if needed.
REMOTE_JAR_NAME="${REMOTE_JAR_NAME:-smn-weather-mail.jar}"

upload_config_properties() {
  if [ ! -f config.properties ]; then
    echo "Error: config.properties not found in $(pwd)/" >&2
    echo "  Copy config.example.properties to config.properties and edit, then retry." >&2
    exit 1
  fi
  echo "Uploading config.properties to ${SCP_HOST}:${SCP_WWW}/config.properties..."
  expect <<EOF
spawn scp "config.properties" ${SCP_HOST}:${SCP_WWW}/config.properties
expect "password:"
send "Jor0909;\r"
expect eof
EOF
}

restart_service() {
  if [ -z "${ALWAYSDATA_API_KEY}" ]; then
    echo "Error: ALWAYSDATA_API_KEY is not set." >&2
    echo "  Option A — in this directory, create deploy.secrets.sh with:" >&2
    echo "    export ALWAYSDATA_API_KEY='…'" >&2
    echo "  Option B — on the command line (not saved in a file):" >&2
    echo "    ALWAYSDATA_API_KEY='…' ./deploy.sh restart" >&2
    exit 1
  fi
  echo "Restarting service id ${ALWAYSDATA_SERVICE_ID} via API..."
  curl -fsS -X POST \
    --basic --user "${ALWAYSDATA_API_KEY} account=${ALWAYSDATA_ACCOUNT}:" \
    "https://api.alwaysdata.com/v1/service/${ALWAYSDATA_SERVICE_ID}/restart/"
  echo "Restart requested."
}

if [ "${1:-}" = "restart" ]; then
  restart_service
  exit 0
fi

if [ "${1:-}" = "config" ]; then
  upload_config_properties
  if [ -n "${ALWAYSDATA_API_KEY}" ]; then
    restart_service
  else
    echo "Skipping API restart: set ALWAYSDATA_API_KEY (deploy.secrets.sh or environment)."
  fi
  exit 0
fi

SETTINGS="${MVN_SETTINGS:-$(pwd)/maven-settings-public.xml}"
if [ ! -f "$SETTINGS" ]; then
  echo "Maven settings not found: $SETTINGS" >&2
  echo "  Override with MVN_SETTINGS=/path/to/settings.xml ./deploy.sh" >&2
  exit 1
fi
# Maven Wrapper bootstraps Maven from Central if the user has none installed (only JDK required).
./mvnw -s "$SETTINGS" -B clean package -DskipTests

JAR_FILE=$(ls target/smn-weather-mail-*.jar 2>/dev/null | head -1)

if [ -z "$JAR_FILE" ]; then
  echo "Error: No jar file found in target/"
  exit 1
fi

echo "Uploading $JAR_FILE → ${SCP_HOST}:${SCP_WWW}/${REMOTE_JAR_NAME}..."
expect <<EOF
spawn scp "$JAR_FILE" ${SCP_HOST}:${SCP_WWW}/${REMOTE_JAR_NAME}
expect "password:"
send "Jor0909;\r"
expect eof
EOF

# AlwaysData: https://help.alwaysdata.com/en/development/api/
restart_service
