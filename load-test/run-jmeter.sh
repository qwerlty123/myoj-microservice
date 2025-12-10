#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-business}"
SCHEME="${SCHEME:-http}"
HOST="${HOST:-localhost}"
PORT="${PORT:-8101}"
THREADS="${THREADS:-50}"
RAMP_UP="${RAMP_UP:-60}"
DURATION="${DURATION:-300}"
QUESTION_ID="${QUESTION_ID:-1}"
COMMENT_QUESTION_ID="${COMMENT_QUESTION_ID:-${QUESTION_ID}}"
TARGET_RPM="${TARGET_RPM:-60}"
ACCOUNTS="${ACCOUNTS:-${SCRIPT_DIR}/accounts.csv}"
ENV_FILE="${ENV_FILE:-${SCRIPT_DIR}/../local-dev.env}"
RUN_ID="${RUN_ID:-$(date '+%Y%m%d-%H%M%S')-${MODE}}"
RESULT_DIR="${SCRIPT_DIR}/results/${RUN_ID}"
SKIP_PREFLIGHT="${SKIP_PREFLIGHT:-false}"
ANALYZE_DB="${ANALYZE_DB:-true}"

if [[ -f "${ENV_FILE}" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "${ENV_FILE}"
  set +a
fi

require_positive_integer() {
  local name="$1"
  local value="$2"
  if [[ ! "${value}" =~ ^[1-9][0-9]*$ ]]; then
    printf '%s must be a positive integer, got: %s\n' "${name}" "${value}" >&2
    exit 1
  fi
}

require_positive_integer THREADS "${THREADS}"
require_positive_integer RAMP_UP "${RAMP_UP}"
require_positive_integer DURATION "${DURATION}"
require_positive_integer QUESTION_ID "${QUESTION_ID}"
require_positive_integer TARGET_RPM "${TARGET_RPM}"

if [[ ! "${RUN_ID}" =~ ^[A-Za-z0-9._-]+$ ]]; then
  printf 'RUN_ID may contain only letters, digits, dot, underscore and dash: %s\n' "${RUN_ID}" >&2
  exit 1
fi

if ! command -v jmeter >/dev/null 2>&1; then
  printf 'JMeter is not installed or is not available on PATH.\n' >&2
  exit 1
fi

if [[ ! -f "${ACCOUNTS}" ]]; then
  printf 'Accounts file not found: %s\nRun prepare-accounts.sh first.\n' "${ACCOUNTS}" >&2
  exit 1
fi

ACCOUNT_COUNT="$(awk 'NR > 1 && NF > 0 { count++ } END { print count + 0 }' "${ACCOUNTS}")"
if (( ACCOUNT_COUNT < THREADS )); then
  printf 'Need at least %s accounts for %s threads, found %s in %s.\n' \
    "${THREADS}" "${MODE}" "${ACCOUNT_COUNT}" "${ACCOUNTS}" >&2
  exit 1
fi

case "${MODE}" in
  business)
    TEST_PLAN="${SCRIPT_DIR}/jmeter/business-flow.jmx"
    ;;
  judge)
    TEST_PLAN="${SCRIPT_DIR}/jmeter/judge-flow.jmx"
    ;;
  *)
    printf 'Usage: %s [business|judge]\n' "$0" >&2
    exit 1
    ;;
esac

preflight() {
  local base_url="${SCHEME}://${HOST}:${PORT}"
  local first_account
  local username
  local password
  local login_response
  local token
  local question_response
  local sandbox_health_url="${SANDBOX_HEALTH_URL:-}"

  first_account="$(awk 'NR == 2 { print; exit }' "${ACCOUNTS}")"
  IFS=',' read -r username password <<< "${first_account}"

  if ! login_response="$(curl --silent --show-error --fail-with-body \
    --connect-timeout 3 --max-time 10 \
    --request POST "${base_url}/api/user/login" \
    --header 'Content-Type: application/json' \
    --data "{\"userAccount\":\"${username}\",\"userPassword\":\"${password}\"}")"; then
    printf 'Preflight could not reach the Gateway login endpoint: %s/api/user/login\n' \
      "${base_url}" >&2
    exit 1
  fi
  token="$(jq --raw-output 'select(.code == 0) | .data.token // empty' <<< "${login_response}")"
  if [[ -z "${token}" ]]; then
    printf 'Preflight login failed. Response: %s\n' "${login_response}" >&2
    exit 1
  fi

  if ! question_response="$(curl --silent --show-error --fail-with-body \
    --connect-timeout 3 --max-time 10 \
    --get "${base_url}/api/question/get/vo" \
    --header "Authorization: Bearer ${token}" \
    --data-urlencode "id=${QUESTION_ID}")"; then
    printf 'Preflight could not query QUESTION_ID=%s through Gateway.\n' \
      "${QUESTION_ID}" >&2
    exit 1
  fi
  # Long IDs are intentionally serialized as JSON strings by the backend to
  # preserve JavaScript precision. Compare their textual forms here.
  if ! jq --exit-status --arg expected "${QUESTION_ID}" \
    '.code == 0 and (.data.id | tostring) == $expected' \
    <<< "${question_response}" >/dev/null; then
    printf 'Preflight question check failed for QUESTION_ID=%s. Response: %s\n' \
      "${QUESTION_ID}" "${question_response}" >&2
    exit 1
  fi

  if [[ -z "${sandbox_health_url}" && "${CODESANDBOX_URL:-}" == */executeCode ]]; then
    sandbox_health_url="${CODESANDBOX_URL%/executeCode}/actuator/health"
  fi
  if [[ "${MODE}" == judge && -n "${sandbox_health_url}" ]]; then
    if ! curl --silent --show-error --fail \
      --connect-timeout 3 --max-time 10 "${sandbox_health_url}" \
      | jq --exit-status '.status == "UP"' >/dev/null; then
      printf 'Remote sandbox health check failed: %s\n' "${sandbox_health_url}" >&2
      exit 1
    fi
  fi

  printf 'Preflight passed: gateway, login, question %s' "${QUESTION_ID}"
  if [[ "${MODE}" == judge && -n "${sandbox_health_url}" ]]; then
    printf ', remote sandbox'
  fi
  printf '.\n'
}

if [[ "${SKIP_PREFLIGHT}" != true ]]; then
  for command_name in curl jq; do
    if ! command -v "${command_name}" >/dev/null 2>&1; then
      printf '%s is required for preflight checks.\n' "${command_name}" >&2
      exit 1
    fi
  done
  preflight
fi

if [[ -e "${RESULT_DIR}" ]]; then
  printf 'Result directory already exists; choose another RUN_ID: %s\n' "${RESULT_DIR}" >&2
  exit 1
fi
mkdir -p "${RESULT_DIR}"

{
  printf 'run_id=%s\n' "${RUN_ID}"
  printf 'mode=%s\n' "${MODE}"
  printf 'gateway=%s://%s:%s\n' "${SCHEME}" "${HOST}" "${PORT}"
  printf 'threads=%s\n' "${THREADS}"
  printf 'ramp_up_seconds=%s\n' "${RAMP_UP}"
  printf 'duration_seconds=%s\n' "${DURATION}"
  printf 'question_id=%s\n' "${QUESTION_ID}"
  printf 'target_rpm=%s\n' "${TARGET_RPM}"
  printf 'started_at=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')"
} > "${RESULT_DIR}/run.properties"

set +e
jmeter -n \
  -t "${TEST_PLAN}" \
  -Jscheme="${SCHEME}" \
  -Jhost="${HOST}" \
  -Jport="${PORT}" \
  -Jthreads="${THREADS}" \
  -Jramp_up="${RAMP_UP}" \
  -Jduration="${DURATION}" \
  -Jquestion_id="${QUESTION_ID}" \
  -Jcomment_question_id="${COMMENT_QUESTION_ID}" \
  -Jtarget_rpm="${TARGET_RPM}" \
  -Jrun_id="${RUN_ID}" \
  -Jaccounts="${ACCOUNTS}" \
  -l "${RESULT_DIR}/results.jtl" \
  -j "${RESULT_DIR}/jmeter.log" \
  -e -o "${RESULT_DIR}/report"
JMETER_STATUS=$?
set -e

printf 'finished_at=%s\n' "$(date '+%Y-%m-%dT%H:%M:%S%z')" >> "${RESULT_DIR}/run.properties"

if [[ -s "${RESULT_DIR}/results.jtl" ]]; then
  "${SCRIPT_DIR}/analyze-jtl.py" "${RESULT_DIR}/results.jtl" \
    | tee "${RESULT_DIR}/api-summary.txt"
fi

if [[ "${MODE}" == judge && "${ANALYZE_DB}" == true && ${JMETER_STATUS} -eq 0 ]]; then
  if ! ENV_FILE="${ENV_FILE}" DRAIN_TIMEOUT="${DRAIN_TIMEOUT:-300}" \
    "${SCRIPT_DIR}/analyze-judge-db.sh" "${RUN_ID}" "${RESULT_DIR}"; then
    printf 'Database end-to-end analysis did not complete; JMeter artifacts are still available.\n' >&2
  fi
fi

printf 'Result: %s\n' "${RESULT_DIR}"
if [[ -f "${RESULT_DIR}/report/index.html" ]]; then
  printf 'HTML report: %s\n' "${RESULT_DIR}/report/index.html"
fi
if [[ -f "${RESULT_DIR}/api-summary.txt" ]]; then
  printf 'API summary: %s\n' "${RESULT_DIR}/api-summary.txt"
fi
if [[ "${MODE}" == judge && -f "${RESULT_DIR}/judge-db-summary.txt" ]]; then
  printf 'Judge end-to-end summary: %s\n' "${RESULT_DIR}/judge-db-summary.txt"
fi

exit "${JMETER_STATUS}"
