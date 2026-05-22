#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

LIB="$ROOT/target/lib"
# Newest matching jar wins if several versions remain under target/ (e.g. after bumping pom).
JAR=$(ls -t "$ROOT/target"/smn-weather-mail-*.jar 2>/dev/null | head -n1)

mail_user="${MAIL_SMTP_USER:-${GMAIL_SMTP_USER:-}}"
mail_pass="${MAIL_SMTP_PASSWORD:-${GMAIL_SMTP_PASSWORD:-}}"
mail_host="${MAIL_SMTP_HOST:-${GMAIL_SMTP_HOST:-}}"
CONFIG_FILE="${SMN_CONFIG_FILE:-$ROOT/config.properties}"

if [[ (-z "$mail_host" || -z "$mail_user" || -z "$mail_pass") && ! -f "$CONFIG_FILE" ]]; then
  echo "Set SMTP via environment variables or create $CONFIG_FILE (see config.example.properties)." >&2
  echo "Example env (Brevo):" >&2
  echo "  export MAIL_SMTP_HOST=smtp-relay.brevo.com" >&2
  echo "  export MAIL_SMTP_USER=<your Brevo SMTP login>" >&2
  echo "  export MAIL_SMTP_PASSWORD=<SMTP key>" >&2
  echo "  export MAIL_FROM=<verified sender>" >&2
  echo "See SETUP.txt" >&2
  exit 1
fi

if command -v mvn >/dev/null 2>&1; then
  MVN_CMD=(mvn)
elif [[ -x "$ROOT/mvnw" ]]; then
  # Maven Wrapper bootstraps Maven into ~/.m2/wrapper/dists on first run; only JDK required.
  MVN_CMD=("$ROOT/mvnw")
else
  echo "Neither system 'mvn' nor './mvnw' (Maven Wrapper) is available." >&2
  echo "  Add the wrapper or install Maven (brew install maven)." >&2
  exit 1
fi

SETTINGS="${MVN_SETTINGS:-$ROOT/maven-settings-public.xml}"
if [[ ! -f "$SETTINGS" ]]; then
  echo "Maven settings not found: $SETTINGS" >&2
  exit 1
fi

echo "Building with ${MVN_CMD[0]} (Maven Central via -s $SETTINGS)..."
"${MVN_CMD[@]}" -q -s "$SETTINGS" package -DskipTests

if [[ ! -f "$JAR" ]]; then
  echo "Expected JAR missing: $JAR" >&2
  exit 1
fi

if [[ -f "$CONFIG_FILE" && -z "$mail_pass" ]] \
    && grep -qE '^[[:space:]]*mail\.smtp\.password[[:space:]]*=[[:space:]]*ENC1:' "$CONFIG_FILE" 2>/dev/null; then
  if [[ -z "${SMN_MASTER_PASSWORD:-}" ]]; then
    echo "config uses ENC1 mail.smtp.password — export SMN_MASTER_PASSWORD with the encryption passphrase." >&2
    exit 1
  fi
fi

# Extra args are passed through, e.g. current=Salta | extreme | add extreme | tiepre-extrema (see WeatherMailApplication).
exec java -cp "$JAR:$LIB/*" ar.gob.smn.weather.WeatherMailApplication "$@"
