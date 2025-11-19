#!/usr/bin/env bash
set -euo pipefail

# Deploy only the Qdrant vector database used by the Spring AI RAG module.
# The full infrastructure deploy.sh also starts Qdrant; this entry point is
# useful when the application stack is already running and only RAG storage
# needs to be installed or upgraded.

SCRIPT_DIR="$(cd "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${SCRIPT_DIR}/.env"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"

for command_name in docker curl; do
  if ! command -v "${command_name}" >/dev/null 2>&1; then
    printf 'Required command is missing: %s\n' "${command_name}" >&2
    exit 1
  fi
done

if ! docker compose version >/dev/null 2>&1; then
  printf 'Docker Compose v2 is required (docker compose).\n' >&2
  exit 1
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Missing %s. Copy .env.example to it and review the values first.\n' "${ENV_FILE}" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1091
source "${ENV_FILE}"
set +a

if [[ -z "${QDRANT_API_KEY:-}" || "${QDRANT_API_KEY}" == CHANGE_ME* ]]; then
  printf 'Set a real QDRANT_API_KEY in %s before deployment.\n' "${ENV_FILE}" >&2
  exit 1
fi
if [[ "${#QDRANT_API_KEY}" -lt 16 ]]; then
  printf 'QDRANT_API_KEY must contain at least 16 characters.\n' >&2
  exit 1
fi
if [[ -z "${QDRANT_HTTP_PORT:-}" || -z "${QDRANT_GRPC_PORT:-}" ]]; then
  printf 'QDRANT_HTTP_PORT and QDRANT_GRPC_PORT must be configured in .env.\n' >&2
  exit 1
fi

COMPOSE=(docker compose
  --project-directory "${SCRIPT_DIR}"
  --file "${COMPOSE_FILE}"
  --env-file "${ENV_FILE}")

# The normal .env binds to 0.0.0.0. If a caller chooses a specific bind IP,
# use that address for the local verification request instead of assuming
# that the port is also available on loopback.
case "${BIND_IP:-0.0.0.0}" in
  0.0.0.0|::|'') QDRANT_CHECK_HOST="127.0.0.1" ;;
  *) QDRANT_CHECK_HOST="${BIND_IP}" ;;
esac
QDRANT_URL="http://${QDRANT_CHECK_HOST}:${QDRANT_HTTP_PORT}"

wait_until_ready() {
  local attempts=30
  local attempt
  for attempt in $(seq 1 "${attempts}"); do
    if curl --fail --silent --show-error --max-time 5 "${QDRANT_URL}/healthz" >/dev/null; then
      printf 'Qdrant is ready.\n'
      return 0
    fi
    sleep 2
  done
  printf 'Qdrant did not become ready in time. Check: %s\n' "${COMPOSE[*]} logs qdrant" >&2
  return 1
}

printf 'Pulling Qdrant image: %s\n' "${QDRANT_IMAGE:-qdrant/qdrant}"
"${COMPOSE[@]}" pull qdrant
"${COMPOSE[@]}" up -d qdrant
wait_until_ready

# Verify that the configured API key can access the HTTP API. The collection
# endpoint is intentionally used instead of logging or printing the key.
if ! curl --fail --silent --show-error --max-time 5 \
    --header "api-key: ${QDRANT_API_KEY}" \
    "${QDRANT_URL}/collections" >/dev/null; then
  printf 'Qdrant is healthy but API-key authentication failed. Check QDRANT_API_KEY and the container logs.\n' >&2
  exit 1
fi

if curl --fail --silent --max-time 5 "${QDRANT_URL}/collections" >/dev/null 2>&1; then
  printf 'Warning: unauthenticated Qdrant collection access succeeded; verify the image/configuration.\n' >&2
else
  printf 'Qdrant API-key authentication is enabled.\n'
fi

printf '\nRAG database deployment completed.\n'
QDRANT_PUBLIC_HOST="${SERVER_PUBLIC_IP:-${BIND_IP:-127.0.0.1}}"
printf 'HTTP endpoint: http://%s:%s\n' "${QDRANT_PUBLIC_HOST}" "${QDRANT_HTTP_PORT}"
printf 'gRPC endpoint: %s:%s\n' "${QDRANT_PUBLIC_HOST}" "${QDRANT_GRPC_PORT}"
"${COMPOSE[@]}" ps qdrant
