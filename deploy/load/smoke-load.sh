#!/usr/bin/env bash
# Short burst through the entry gateway — 经入口网关打一串短突发（不是基准测试）。
# Usage: GATEWAY_BASE=http://127.0.0.1:8088 REQUESTS=40 ./deploy/load/smoke-load.sh
set -euo pipefail

GATEWAY_BASE="${GATEWAY_BASE:-http://127.0.0.1:8088}"
REQUESTS="${REQUESTS:-40}"
PATH_UNDER_TEST="${PATH_UNDER_TEST:-/actuator/health}"
AUTH_HEADER="${AUTH_HEADER:-}"

echo "smoke-load: ${REQUESTS} GET ${GATEWAY_BASE}${PATH_UNDER_TEST}"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

seq 1 "$REQUESTS" | while read -r i; do
  args=(-sS -o /dev/null -w "%{http_code}\n" "${GATEWAY_BASE}${PATH_UNDER_TEST}")
  if [[ -n "$AUTH_HEADER" ]]; then
    args=(-sS -o /dev/null -w "%{http_code}\n" -H "Authorization: ${AUTH_HEADER}" "${GATEWAY_BASE}${PATH_UNDER_TEST}")
  fi
  curl "${args[@]}" >>"$tmpdir/codes.txt" || echo "000" >>"$tmpdir/codes.txt"
done

echo "status distribution:"
sort "$tmpdir/codes.txt" | uniq -c | sort -nr
ok="$(grep -c '^200$' "$tmpdir/codes.txt" || true)"
echo "ok_200=${ok}/${REQUESTS}"
# Pass when a majority succeed (rate limit or cold start may trim a few).
# 多数成功即过（限流或冷启动可能抹掉少数请求）。
need=$(( REQUESTS * 7 / 10 ))
if [[ "$ok" -lt "$need" ]]; then
  echo "smoke-load failed: fewer than 70% returned 200" >&2
  exit 1
fi
echo "smoke-load ok"
