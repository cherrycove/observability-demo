#!/usr/bin/env bash
set -euo pipefail

base_url="${DEMO_BASE_URL:-http://127.0.0.1:8080}"
count="${TRAFFIC_COUNT:-30}"
interval="${TRAFFIC_INTERVAL_SECONDS:-1}"

for ((index = 1; index <= count; index += 1)); do
  request_id="biz-traffic-$(date +%s)-${index}"
  curl --fail --silent --show-error \
    -H 'X-Key-Request: checkout_submit_order' \
    -H "X-Business-Request-Id: ${request_id}" \
    "${base_url}/api/orders/demo" >/dev/null
  printf 'request %d/%d: %s\n' "${index}" "${count}" "${request_id}"
  sleep "${interval}"
done
