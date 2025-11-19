#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${SCRIPT_DIR}"

if [[ ! -f .env ]]; then
  printf 'Missing .env.\n' >&2
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

BACKUP_ROOT="${BACKUP_ROOT:-${SCRIPT_DIR}/backups}"
BACKUP_DIR="${BACKUP_ROOT}/$(date '+%Y%m%d-%H%M%S')"
mkdir -p "${BACKUP_DIR}"
chmod 700 "${BACKUP_DIR}"

printf 'Backing up MySQL...\n'
"${COMPOSE[@]}" exec -T mysql mysqldump \
  -uroot "-p${MYSQL_ROOT_PASSWORD}" \
  --single-transaction --routines --events --databases "${MYSQL_DATABASE}" \
  | gzip > "${BACKUP_DIR}/mysql.sql.gz"

printf 'Backing up Redis...\n'
"${COMPOSE[@]}" exec -T redis redis-cli --no-auth-warning -a "${REDIS_PASSWORD}" SAVE >/dev/null
docker cp myoj-redis:/data/dump.rdb "${BACKUP_DIR}/redis-dump.rdb" >/dev/null

services_stopped=0
restart_stateful_services() {
  if [[ "${services_stopped}" == "1" ]]; then
    "${COMPOSE[@]}" start nacos minio qdrant >/dev/null
  fi
}
trap restart_stateful_services EXIT

printf 'Briefly stopping Nacos, MinIO and Qdrant for consistent volume archives...\n'
"${COMPOSE[@]}" stop nacos minio qdrant >/dev/null
services_stopped=1

docker run --rm \
  -v myoj-nacos-data:/source:ro \
  -v "${BACKUP_DIR}:/backup" \
  alpine:3.20 tar -czf /backup/nacos-data.tar.gz -C /source .

docker run --rm \
  -v myoj-minio-data:/source:ro \
  -v "${BACKUP_DIR}:/backup" \
  alpine:3.20 tar -czf /backup/minio-data.tar.gz -C /source .

docker run --rm \
  -v myoj-qdrant-data:/source:ro \
  -v "${BACKUP_DIR}:/backup" \
  alpine:3.20 tar -czf /backup/qdrant-data.tar.gz -C /source .

restart_stateful_services
services_stopped=0
trap - EXIT

printf 'Backup completed: %s\n' "${BACKUP_DIR}"
