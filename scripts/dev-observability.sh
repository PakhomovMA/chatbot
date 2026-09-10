#!/usr/bin/env bash
# Local dev helper: start chatbot with the `metrics` profile + Prometheus/Grafana stack.
# Usage:
#   scripts/dev-observability.sh up     — start everything (default)
#   scripts/dev-observability.sh down   — stop app and monitoring stack, keep data
#   scripts/dev-observability.sh logs   — tail the app log
set -euo pipefail

cd "$(dirname "$0")/.."

COMPOSE="docker compose -f ops/observability/compose.yaml --profile metrics"
APP_LOG="${CHATBOT_DEV_LOG:-/tmp/chatbot-app.log}"
APP_PID_FILE=/tmp/chatbot-app.pid
MGMT_URL="http://127.0.0.1:8081/actuator/health"

app_running() {
  [[ -f $APP_PID_FILE ]] && kill -0 "$(cat "$APP_PID_FILE")" 2>/dev/null
}

wait_for_mgmt_health() {
  for _ in $(seq 1 120); do
    if curl -sf "$MGMT_URL" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "App did not become healthy on $MGMT_URL in time; see $APP_LOG" >&2
  return 1
}

up() {
  if [[ ! -f ops/observability/.env ]]; then
    cp -n ops/observability/.env.example ops/observability/.env
    echo "Created ops/observability/.env from example — set your GRAFANA_ADMIN_PASSWORD there."
  fi

  if ! curl -sf http://127.0.0.1:11434/api/tags >/dev/null 2>&1; then
    if command -v ollama >/dev/null 2>&1; then
      echo "Starting Ollama..."
      (ollama serve >/tmp/ollama.log 2>&1 &)
    else
      echo "WARNING: Ollama is not reachable on :11434 and not installed; the app will report ollama DOWN."
    fi
  fi

  if app_running; then
    echo "App already running (pid $(cat "$APP_PID_FILE"))."
  else
    echo "Starting chatbot with profile 'metrics' (log: $APP_LOG)..."
    SPRING_PROFILES_ACTIVE=metrics nohup ./gradlew bootRun >"$APP_LOG" 2>&1 &
    echo $! >"$APP_PID_FILE"
  fi

  echo "Starting monitoring stack..."
  $COMPOSE up -d --wait

  echo "Waiting for app health on $MGMT_URL ..."
  wait_for_mgmt_health

  cat <<EOF

Everything is up:
  App (API):      http://127.0.0.1:8080
  Grafana:        http://127.0.0.1:3001   (login from ops/observability/.env)
  Prometheus:     http://127.0.0.1:9090/targets  (job "chatbot" should be UP)
  App metrics:    $MGMT_URL

Stop with: scripts/dev-observability.sh down
Tail logs: scripts/dev-observability.sh logs
EOF
}

down() {
  echo "Stopping monitoring stack (data preserved)..."
  $COMPOSE stop
  if app_running; then
    echo "Stopping chatbot (pid $(cat "$APP_PID_FILE"))..."
    kill "$(cat "$APP_PID_FILE")" 2>/dev/null || true
    # gradle wrapper spawns a JVM child; stop it too.
    pkill -f "chatbot.*bootRun" 2>/dev/null || true
    rm -f "$APP_PID_FILE"
  else
    echo "App is not running."
  fi
  echo "Done. Data volumes (prometheus-data, grafana-data) are preserved."
}

case "${1:-up}" in
  up) up ;;
  down) down ;;
  logs) exec tail -f "$APP_LOG" ;;
  *) echo "Usage: $0 [up|down|logs]" >&2; exit 2 ;;
esac
