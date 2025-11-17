#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8101}"
ACCOUNT_COUNT="${ACCOUNT_COUNT:-100}"
ACCOUNT_PREFIX="${ACCOUNT_PREFIX:-loadtest}"
ACCOUNT_PASSWORD="${ACCOUNT_PASSWORD:-LoadTest123!}"
OUTPUT_FILE="${OUTPUT_FILE:-accounts.csv}"

printf 'username,password\n' > "${OUTPUT_FILE}"

for number in $(seq 1 "${ACCOUNT_COUNT}"); do
  username="$(printf '%s%04d' "${ACCOUNT_PREFIX}" "${number}")"
  response="$(curl --silent --show-error \
    --request POST "${BASE_URL}/api/user/register" \
    --header 'Content-Type: application/json' \
    --data "{\"userAccount\":\"${username}\",\"userPassword\":\"${ACCOUNT_PASSWORD}\",\"checkPassword\":\"${ACCOUNT_PASSWORD}\"}")"

  if [[ "${response}" == *'"code":0'* ]] || [[ "${response}" == *'账号重复'* ]]; then
    printf '%s,%s\n' "${username}" "${ACCOUNT_PASSWORD}" >> "${OUTPUT_FILE}"
  else
    printf 'Failed to prepare %s: %s\n' "${username}" "${response}" >&2
  fi
done

printf 'Prepared accounts file: %s\n' "${OUTPUT_FILE}"
