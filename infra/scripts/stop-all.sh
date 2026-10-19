#!/usr/bin/env bash
# 停止本机基础设施进程
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOGS="$ROOT/infra/bin/logs"

for svc in minio keycloak; do
  [ -f "$LOGS/$svc.pid" ] || continue
  PID="$(cat "$LOGS/$svc.pid")"
  if kill -0 "$PID" 2>/dev/null; then
    kill "$PID" && echo "已停止 $svc (pid $PID)"
  fi
  rm -f "$LOGS/$svc.pid"
done
