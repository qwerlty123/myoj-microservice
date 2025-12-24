#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-business}"
HOST="${HOST:-localhost}"
PORT="${PORT:-8101}"
THREADS="${THREADS:-50}"
RAMP_UP="${RAMP_UP:-60}"
DURATION="${DURATION:-300}"
QUESTION_ID="${QUESTION_ID:-1}"
COMMENT_QUESTION_ID="${COMMENT_QUESTION_ID:-${QUESTION_ID}}"
TARGET_RPM="${TARGET_RPM:-60}"
ACCOUNTS="${ACCOUNTS:-${SCRIPT_DIR}/accounts.csv}"
RUN_ID="$(date '+%Y%m%d-%H%M%S')-${MODE}"
RESULT_DIR="${SCRIPT_DIR}/results/${RUN_ID}"

if ! command -v jmeter >/dev/null 2>&1; then
  printf 'JMeter is not installed or is not available on PATH.\n' >&2
  exit 1
fi

if [[ ! -f "${ACCOUNTS}" ]]; then
  printf 'Accounts file not found: %s\nRun prepare-accounts.sh first.\n' "${ACCOUNTS}" >&2
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

mkdir -p "${RESULT_DIR}/report"

jmeter -n \
  -t "${TEST_PLAN}" \
  -Jhost="${HOST}" \
  -Jport="${PORT}" \
  -Jthreads="${THREADS}" \
  -Jramp_up="${RAMP_UP}" \
  -Jduration="${DURATION}" \
  -Jquestion_id="${QUESTION_ID}" \
  -Jcomment_question_id="${COMMENT_QUESTION_ID}" \
  -Jtarget_rpm="${TARGET_RPM}" \
  -Jaccounts="${ACCOUNTS}" \
  -l "${RESULT_DIR}/results.jtl" \
  -j "${RESULT_DIR}/jmeter.log" \
  -e -o "${RESULT_DIR}/report"

printf 'Result: %s\n' "${RESULT_DIR}"
printf 'HTML report: %s\n' "${RESULT_DIR}/report/index.html"
