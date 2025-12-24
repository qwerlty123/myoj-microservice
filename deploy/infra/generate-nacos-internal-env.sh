#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INTERNAL_ENV_FILE="${SCRIPT_DIR}/.env.internal"

if [[ -f "${INTERNAL_ENV_FILE}" ]]; then
  printf '%s already exists; keeping the existing Nacos signing keys.\n' "${INTERNAL_ENV_FILE}"
  exit 0
fi

if ! command -v openssl >/dev/null 2>&1; then
  printf 'openssl is required to generate Nacos internal signing keys.\n' >&2
  exit 1
fi

umask 077
cat > "${INTERNAL_ENV_FILE}" <<EOF
NACOS_AUTH_TOKEN=$(openssl rand -base64 48 | tr -d '\n')
NACOS_AUTH_IDENTITY_KEY=$(openssl rand -hex 24)
NACOS_AUTH_IDENTITY_VALUE=$(openssl rand -hex 24)
EOF

printf 'Generated Nacos internal keys: %s\n' "${INTERNAL_ENV_FILE}"
printf 'These are not login credentials and normally do not need to be viewed or edited.\n'
