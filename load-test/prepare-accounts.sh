#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8101}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-100}"
ACCOUNT_PREFIX="${ACCOUNT_PREFIX:-loadtest}"
ACCOUNT_PASSWORD="${ACCOUNT_PASSWORD:-LoadTest123!}"
OUTPUT_FILE="${OUTPUT_FILE:-${SCRIPT_DIR}/accounts.csv}"

if [[ ! "${ACCOUNT_COUNT}" =~ ^[1-9][0-9]*$ ]]; then
  printf 'ACCOUNT_COUNT must be a positive integer, got: %s\n' "${ACCOUNT_COUNT}" >&2
  exit 1
fi

if ! command -v jq >/dev/null 2>&1; then
  printf 'jq is required to prepare accounts.\n' >&2
  exit 1
fi

printf 'username,password\n' > "${OUTPUT_FILE}"
failed=0

for number in $(seq 1 "${ACCOUNT_COUNT}"); do
  username="$(printf '%s%04d' "${ACCOUNT_PREFIX}" "${number}")"
  request_body="$(jq --null-input --compact-output \
    --arg account "${username}" --arg password "${ACCOUNT_PASSWORD}" \
    '{userAccount: $account, userPassword: $password, checkPassword: $password}')"
  response="$(curl --silent --show-error \
    --request POST "${BASE_URL}/api/user/register" \
    --header 'Content-Type: application/json' \
    --data "${request_body}")"

  if jq --exit-status '.code == 0 or (.message // "" | contains("账号重复"))' \
    <<< "${response}" >/dev/null; then
    printf '%s,%s\n' "${username}" "${ACCOUNT_PASSWORD}" >> "${OUTPUT_FILE}"
  else
    printf 'Failed to prepare %s: %s\n' "${username}" "${response}" >&2
    failed=$((failed + 1))
  fi
done

printf 'Prepared accounts file: %s\n' "${OUTPUT_FILE}"
if (( failed > 0 )); then
  printf '%s accounts failed to prepare; fix them before running the load test.\n' "${failed}" >&2
  exit 1
fi
