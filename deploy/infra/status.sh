#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -f .env ]]; then
  printf 'Missing .env. Infrastructure has not been initialized.\n' >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ ! -f .env.internal ]]; then
  printf 'Missing .env.internal. Run deploy.sh first.\n' >&2
  exit 1
fi

COMPOSE=(docker compose --env-file .env --env-file .env.internal)

"${COMPOSE[@]}" ps

printf '\nEndpoint checks:\n'
curl --fail --silent "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/health/liveness" && printf '  Nacos: OK\n' || printf '  Nacos: FAILED\n'
curl --fail --silent "http://127.0.0.1:${MINIO_PORT}/minio/health/live" && printf '  MinIO: OK\n' || printf '  MinIO: FAILED\n'
"${COMPOSE[@]}" exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot "-p${MYSQL_ROOT_PASSWORD}" --silent >/dev/null && printf '  MySQL: OK\n' || printf '  MySQL: FAILED\n'
"${COMPOSE[@]}" exec -T redis redis-cli --no-auth-warning -a "${REDIS_PASSWORD}" ping >/dev/null && printf '  Redis: OK\n' || printf '  Redis: FAILED\n'
"${COMPOSE[@]}" exec -T rabbitmq rabbitmq-diagnostics -q ping >/dev/null && printf '  RabbitMQ: OK\n' || printf '  RabbitMQ: FAILED\n'
