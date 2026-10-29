#!/usr/bin/env bash
# 基础设施健康检查
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

MINIO_API="${SM_MINIO_ENDPOINT:-http://127.0.0.1:9100}"
MINIO_CONSOLE="${SM_MINIO_CONSOLE:-http://127.0.0.1:9101}"
API="${SM_API_BASE:-http://127.0.0.1:8080/api/v1}"

fail=0
check() {
  if curl -sf -o /dev/null "$2"; then
    echo "  OK   $1"
  else
    echo "  FAIL $1 ($2)"
    fail=1
  fi
}

echo "基础设施健康检查："
check "MinIO 健康"          "$MINIO_API/minio/health/live"
check "MinIO 控制台"        "$MINIO_CONSOLE/"
# 公开配置在就行：登录方式（内置账号或外部 IdP）由它说了算。
check "服务端公开配置"      "$API/config/public"

exit $fail
