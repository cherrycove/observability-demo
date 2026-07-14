#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root_dir}"

token_prefix='tkn''_'
workspace_prefix='wksp''_'
patterns=(
  "${token_prefix}[A-Za-z0-9]{16,}"
  "${workspace_prefix}[A-Za-z0-9]{16,}"
  '-----BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY-----'
  'https?://[^[:space:]]+[?&]token=[A-Za-z0-9._-]{12,}'
)

failed=0
for pattern in "${patterns[@]}"; do
  if rg --hidden --glob '!.git/**' --glob '!scripts/secret-scan.sh' --regexp "${pattern}" .; then
    failed=1
  fi
done

if command -v gitleaks >/dev/null 2>&1; then
  gitleaks dir --config .gitleaks.toml --no-banner --redact . || failed=1
fi

if [[ "${failed}" -ne 0 ]]; then
  echo "secret scan failed" >&2
  exit 1
fi
echo "secret scan passed"
