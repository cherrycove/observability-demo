#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
assets_dir="${root_dir}/order-service/src/main/resources/static/assets"
output_dir="${root_dir}/dist/rum-sourcemap"
archive="${root_dir}/dist/guance-observability-demo-rum-sourcemap.zip"

rm -rf "${output_dir}"
mkdir -p "${output_dir}/assets/src"
cp "${assets_dir}/checkout-sourcemap-fault.min.js" "${output_dir}/assets/"
cp "${assets_dir}/checkout-sourcemap-fault.min.js.map" "${output_dir}/assets/"
cp "${assets_dir}/src/checkout-sourcemap-fault.js" "${output_dir}/assets/src/"
rm -f "${archive}"
(cd "${output_dir}" && zip -qr "${archive}" .)

echo "created ${archive}"
