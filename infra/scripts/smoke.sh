#!/usr/bin/env bash
# 基础设施健康检查
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

MINIO_API="${SM_MINIO_ENDPOINT:-http://127.0.0.1:9100}"
MINIO_CONSOLE="${SM_MINIO_CONSOLE:-http://127.0.0.1:9101}"
ISSUER="${SM_KEYCLOAK_ISSUER:-http://127.0.0.1:8180/kc/realms/skillmesh}"

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
check "Keycloak OIDC 发现"  "$ISSUER/.well-known/openid-configuration"
check "Keycloak JWKS"       "$ISSUER/protocol/openid-connect/certs"

exit $fail
