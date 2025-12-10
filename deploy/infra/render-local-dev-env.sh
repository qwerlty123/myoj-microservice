#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_ENV_FILE="${SCRIPT_DIR}/local-dev.env"

if [[ ! -f "${SCRIPT_DIR}/.env" ]]; then
  printf 'Missing %s/.env.\n' "${SCRIPT_DIR}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/.env"
set +a

if [[ -z "${SERVER_PUBLIC_IP:-}" || "${SERVER_PUBLIC_IP}" == CHANGE_ME* ]]; then
  printf 'Set SERVER_PUBLIC_IP in %s/.env first.\n' "${SCRIPT_DIR}" >&2
  exit 1
fi

CODESANDBOX_HOST="${CODESANDBOX_HOST:-${SERVER_PUBLIC_IP}}"
NACOS_DISCOVERY_GROUP="${LOCAL_NACOS_DISCOVERY_GROUP:-LOCAL_DEV_GROUP}"

umask 077
# local-dev.env is sourced by Bash/Zsh. Use %q so URLs containing '&' and
# secrets containing shell metacharacters remain one valid shell assignment.
write_env_value() {
  printf '%s=%q\n' "$1" "$2"
}

{
  write_env_value SPRING_PROFILES_ACTIVE prod
  write_env_value MYSQL_URL "jdbc:mysql://${SERVER_PUBLIC_IP}:${MYSQL_PORT}/${MYSQL_DATABASE}?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
  write_env_value MYSQL_USERNAME "${MYSQL_USER}"
  write_env_value MYSQL_PASSWORD "${MYSQL_PASSWORD}"
  write_env_value REDIS_HOST "${SERVER_PUBLIC_IP}"
  write_env_value REDIS_PORT "${REDIS_PORT}"
  write_env_value REDIS_PASSWORD "${REDIS_PASSWORD}"
  write_env_value NACOS_SERVER_ADDR "${SERVER_PUBLIC_IP}:${NACOS_PORT}"
  write_env_value NACOS_USERNAME nacos
  write_env_value NACOS_PASSWORD "${NACOS_ADMIN_PASSWORD}"
  write_env_value NACOS_DISCOVERY_GROUP "${NACOS_DISCOVERY_GROUP}"
  write_env_value RABBITMQ_HOST "${SERVER_PUBLIC_IP}"
  write_env_value RABBITMQ_PORT "${RABBITMQ_PORT}"
  write_env_value RABBITMQ_USERNAME "${RABBITMQ_DEFAULT_USER}"
  write_env_value RABBITMQ_PASSWORD "${RABBITMQ_DEFAULT_PASS}"
  write_env_value QDRANT_HOST "${SERVER_PUBLIC_IP}"
  write_env_value QDRANT_HTTP_PORT "${QDRANT_HTTP_PORT}"
  write_env_value QDRANT_GRPC_PORT "${QDRANT_GRPC_PORT}"
  write_env_value QDRANT_API_KEY "${QDRANT_API_KEY}"
  write_env_value AI_INTERNAL_TOKEN "${AI_INTERNAL_TOKEN}"
  write_env_value GATEWAY_TRUST_TOKEN "${GATEWAY_TRUST_TOKEN}"
  write_env_value AI_GENERATION_PUBLIC_ENABLED "${AI_GENERATION_PUBLIC_ENABLED:-false}"
  write_env_value AI_MODEL_PUBLIC_CONCURRENCY "${AI_MODEL_PUBLIC_CONCURRENCY:-0}"
  write_env_value AI_MODEL_REVIEW_CONCURRENCY "${AI_MODEL_REVIEW_CONCURRENCY:-0}"
  write_env_value AI_SANDBOX_PUBLIC_CONCURRENCY "${AI_SANDBOX_PUBLIC_CONCURRENCY:-2}"
  write_env_value AI_SANDBOX_REVIEW_CONCURRENCY "${AI_SANDBOX_REVIEW_CONCURRENCY:-1}"
  write_env_value AI_PUBLIC_DAILY_BUDGET_MICROS "${AI_PUBLIC_DAILY_BUDGET_MICROS:-0}"
  write_env_value AI_REVIEW_DAILY_BUDGET_MICROS "${AI_REVIEW_DAILY_BUDGET_MICROS:-0}"
  write_env_value AI_INPUT_PRICE_MICROS_PER_MILLION "${AI_INPUT_PRICE_MICROS_PER_MILLION:-0}"
  write_env_value AI_OUTPUT_PRICE_MICROS_PER_MILLION "${AI_OUTPUT_PRICE_MICROS_PER_MILLION:-0}"
  write_env_value MINIO_ENDPOINT "http://${SERVER_PUBLIC_IP}:${MINIO_PORT}"
  write_env_value MINIO_PUBLIC_ENDPOINT "http://${SERVER_PUBLIC_IP}:${MINIO_PORT}"
  write_env_value MINIO_ACCESS_KEY "${MINIO_ROOT_USER}"
  write_env_value MINIO_SECRET_KEY "${MINIO_ROOT_PASSWORD}"
  write_env_value MINIO_BUCKET "${MINIO_BUCKET}"
  write_env_value CODESANDBOX_URL "http://${CODESANDBOX_HOST}:${CODESANDBOX_PORT}/executeCode"
  write_env_value CODESANDBOX_SECRET_KEY "${CODESANDBOX_SECRET_KEY}"
  write_env_value CODESANDBOX_TIMESTAMP_TOLERANCE_SECONDS "${CODESANDBOX_TIMESTAMP_TOLERANCE_SECONDS:-300}"
  write_env_value LOAD_TEST_CRAWLER_DETECTION_ENABLED "${LOAD_TEST_CRAWLER_DETECTION_ENABLED:-false}"
  write_env_value JUDGE_CONSUMER_CONCURRENCY "${JUDGE_CONSUMER_CONCURRENCY:-2}"
  write_env_value JUDGE_CONSUMER_MAX_CONCURRENCY "${JUDGE_CONSUMER_MAX_CONCURRENCY:-2}"
  write_env_value JUDGE_CONSUMER_PREFETCH "${JUDGE_CONSUMER_PREFETCH:-1}"
  write_env_value JUDGE_OUTBOX_DISPATCH_INTERVAL_MS "${JUDGE_OUTBOX_DISPATCH_INTERVAL_MS:-500}"
  write_env_value JUDGE_OUTBOX_BATCH_SIZE "${JUDGE_OUTBOX_BATCH_SIZE:-50}"
} > "${LOCAL_ENV_FILE}"

printf 'Generated local development settings: %s\n' "${LOCAL_ENV_FILE}"
