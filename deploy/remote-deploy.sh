#!/usr/bin/env bash
# Runs on the droplet (invoked by GitHub Actions or manually).
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/llm-gateway}"
COMPOSE_FILE="${APP_DIR}/deploy/docker-compose.prod.yml"

if [ ! -d "${APP_DIR}/.git" ]; then
  echo "Repository not found at ${APP_DIR} — run setup-droplet.sh first."
  exit 1
fi

if [ -n "${DO_API_KEY:-}" ]; then
  umask 077
  printf 'DO_API_KEY=%s\nSERVER_PORT=8080\n' "${DO_API_KEY}" > "${APP_DIR}/.env"
  chmod 600 "${APP_DIR}/.env"
elif [ ! -f "${APP_DIR}/.env" ]; then
  echo "Missing ${APP_DIR}/.env — create from .env.example or set DO_API_KEY."
  exit 1
fi

cd "${APP_DIR}"
git fetch origin main
git reset --hard origin/main

docker compose -f "${COMPOSE_FILE}" up --build -d
docker compose -f "${COMPOSE_FILE}" ps

echo "Waiting for health check..."
for _ in $(seq 1 30); do
  if curl -sf http://localhost:8080/actuator/health >/dev/null; then
    echo "Health check passed."
    exit 0
  fi
  sleep 5
done

echo "Health check timed out — inspect: docker compose -f ${COMPOSE_FILE} logs"
exit 1
