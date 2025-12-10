#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_ID="${1:-}"
RESULT_DIR="${2:-${SCRIPT_DIR}/results/${RUN_ID}}"
ENV_FILE="${ENV_FILE:-${SCRIPT_DIR}/../local-dev.env}"
DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-300}"
DRAIN_POLL_INTERVAL="${DRAIN_POLL_INTERVAL:-5}"

if [[ ! "${RUN_ID}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  printf 'Usage: %s RUN_ID [RESULT_DIR]\n' "$0" >&2
  exit 2
fi

if [[ ! "${DRAIN_TIMEOUT}" =~ ^[1-9][0-9]*$ || ! "${DRAIN_POLL_INTERVAL}" =~ ^[1-9][0-9]*$ ]]; then
  printf 'DRAIN_TIMEOUT and DRAIN_POLL_INTERVAL must be positive integers.\n' >&2
  exit 2
fi

if [[ ! -f "${ENV_FILE}" ]]; then
  printf 'Environment file not found: %s\n' "${ENV_FILE}" >&2
  exit 1
fi

if ! command -v mysql >/dev/null 2>&1; then
  printf 'mysql client is required for judge end-to-end analysis.\n' >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "${ENV_FILE}"
set +a

if [[ -z "${MYSQL_URL:-}" || -z "${MYSQL_USERNAME:-}" || -z "${MYSQL_PASSWORD:-}" ]]; then
  printf 'MYSQL_URL, MYSQL_USERNAME and MYSQL_PASSWORD are required in %s.\n' "${ENV_FILE}" >&2
  exit 1
fi

jdbc_address="${MYSQL_URL#jdbc:mysql://}"
db_endpoint="${jdbc_address%%/*}"
db_with_query="${jdbc_address#*/}"
db_name="${db_with_query%%\?*}"
if [[ "${db_endpoint}" == *:* ]]; then
  db_host="${db_endpoint%:*}"
  db_port="${db_endpoint##*:}"
else
  db_host="${db_endpoint}"
  db_port=3306
fi

MYSQL_DEFAULTS_FILE="$(mktemp "${TMPDIR:-/tmp}/myoj-load-test-mysql.XXXXXX")"
trap 'rm -f "${MYSQL_DEFAULTS_FILE}"' EXIT
chmod 600 "${MYSQL_DEFAULTS_FILE}"
{
  printf '[client]\n'
  printf 'protocol=tcp\n'
  printf 'host=%s\n' "${db_host}"
  printf 'port=%s\n' "${db_port}"
  printf 'user=%s\n' "${MYSQL_USERNAME}"
  printf 'password=%s\n' "${MYSQL_PASSWORD}"
  printf 'default-character-set=utf8mb4\n'
} > "${MYSQL_DEFAULTS_FILE}"

mysql_scalar() {
  mysql --defaults-extra-file="${MYSQL_DEFAULTS_FILE}" \
    --batch --raw --skip-column-names "${db_name}" --execute="$1"
}

run_predicate="LOCATE(CONCAT('LOAD_TEST_RUN_ID=', '${RUN_ID}'), code) > 0 AND isDelete = 0"
total="$(mysql_scalar "SELECT COUNT(*) FROM question_submit WHERE ${run_predicate};")"
if [[ "${total}" == 0 ]]; then
  printf 'No submissions found for RUN_ID=%s. Check JMeter submission errors.\n' "${RUN_ID}" >&2
  exit 1
fi

printf 'Waiting for RUN_ID=%s to reach terminal state (timeout=%ss)...\n' \
  "${RUN_ID}" "${DRAIN_TIMEOUT}"
deadline=$((SECONDS + DRAIN_TIMEOUT))
timed_out=false
while true; do
  remaining="$(mysql_scalar \
    "SELECT COUNT(*) FROM question_submit WHERE ${run_predicate} AND status IN (0, 1);")"
  if [[ "${remaining}" == 0 ]]; then
    break
  fi
  if (( SECONDS >= deadline )); then
    timed_out=true
    printf 'Drain timeout: %s submissions are still WAITING/RUNNING.\n' "${remaining}" >&2
    break
  fi
  printf '  remaining=%s\n' "${remaining}"
  sleep "${DRAIN_POLL_INTERVAL}"
done

mkdir -p "${RESULT_DIR}"
{
  printf "SET @run_id = '%s';\n" "${RUN_ID}"
  sed -n '1,$p' "${SCRIPT_DIR}/sql/judge-run-summary.sql"
} | mysql --defaults-extra-file="${MYSQL_DEFAULTS_FILE}" \
  --table "${db_name}" | tee "${RESULT_DIR}/judge-db-summary.txt"

if [[ "${timed_out}" == true ]]; then
  exit 1
fi
