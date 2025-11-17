#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

for command_name in docker curl openssl; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'Required command is missing: %s\n' "${command_name}" >&2
    exit 1
  fi
done

if ! docker compose version >/dev/null 2>&1; then
  printf 'Docker Compose v2 is required (docker compose).\n' >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  printf 'Missing %s/.env. Copy .env.example to .env and review the configured values first.\n' "${SCRIPT_DIR}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source .env
set +a

if [[ -z "${SERVER_PUBLIC_IP:-}" || "${SERVER_PUBLIC_IP}" == CHANGE_ME* ]]; then
  printf 'Set SERVER_PUBLIC_IP in %s/.env before deployment.\n' "${SCRIPT_DIR}" >&2
  exit 1
fi

if [[ "${MYSQL_USER:-}" == "root" ]]; then
  printf 'MYSQL_USER cannot be root. Set MYSQL_USER=admin (or another regular user) and keep the root password in MYSQL_ROOT_PASSWORD.\n' >&2
  exit 1
fi

required_secret_vars=(
  MYSQL_PASSWORD MYSQL_ROOT_PASSWORD REDIS_PASSWORD
  NACOS_ADMIN_PASSWORD RABBITMQ_DEFAULT_PASS MINIO_ROOT_PASSWORD CODESANDBOX_SECRET_KEY
)
for secret_name in "${required_secret_vars[@]}"; do
  secret_value="${!secret_name:-}"
  if [[ -z "${secret_value}" || "${secret_value}" == CHANGE_ME* ]]; then
    printf 'Set a real value for %s in %s/.env before deployment.\n' "${secret_name}" "${SCRIPT_DIR}" >&2
    exit 1
  fi
done

./generate-nacos-internal-env.sh
set -a
# shellcheck disable=SC1091
source .env.internal
set +a

COMPOSE=(docker compose --env-file .env --env-file .env.internal)

wait_until() {
  local name="$1"
  local attempts="$2"
  shift 2
  local attempt
  for attempt in $(seq 1 "${attempts}"); do
    if "$@" >/dev/null 2>&1; then
      printf '%s is ready.\n' "${name}"
      return 0
    fi
    sleep 3
  done
  printf '%s did not become ready in time.\n' "${name}" >&2
  return 1
}

"${COMPOSE[@]}" pull mysql redis nacos rabbitmq minio minio-init
"${COMPOSE[@]}" up -d

wait_until MySQL 40 "${COMPOSE[@]}" exec -T mysql mysqladmin ping -h 127.0.0.1 -uroot "-p${MYSQL_ROOT_PASSWORD}" --silent
wait_until Redis 30 "${COMPOSE[@]}" exec -T redis redis-cli --no-auth-warning -a "${REDIS_PASSWORD}" ping
wait_until RabbitMQ 40 "${COMPOSE[@]}" exec -T rabbitmq rabbitmq-diagnostics -q ping
wait_until MinIO 30 curl --fail --silent "http://127.0.0.1:${MINIO_PORT}/minio/health/live"
wait_until Nacos 60 curl --fail --silent "http://127.0.0.1:${NACOS_PORT}/nacos/v1/console/health/liveness"

# Nacos 2.4+ requires the first administrator password to be initialized explicitly.
nacos_admin_result="$(curl --silent --show-error \
  --request POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/auth/users/admin" \
  --data-urlencode "password=${NACOS_ADMIN_PASSWORD}" || true)"
if [[ -n "${nacos_admin_result}" ]]; then
  printf 'Nacos administrator initialization was attempted.\n'
fi

nacos_login_result="$(curl --silent --show-error \
  --request POST "http://127.0.0.1:${NACOS_PORT}/nacos/v1/auth/login" \
  --data-urlencode 'username=nacos' \
  --data-urlencode "password=${NACOS_ADMIN_PASSWORD}" || true)"
if [[ "${nacos_login_result}" != *'accessToken'* ]]; then
  printf 'Nacos administrator login verification failed. Check Nacos logs and the persisted administrator password.\n' >&2
  exit 1
fi

./render-local-dev-env.sh

printf '\nInfrastructure deployment completed.\n'
"${COMPOSE[@]}" ps
printf 'Local development settings are in %s/local-dev.env (mode 600).\n' "${SCRIPT_DIR}"
printf 'Keep .env, .env.internal, and local-dev.env off Git.\n'
