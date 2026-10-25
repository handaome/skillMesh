#!/usr/bin/env bash
# 启动本机服务（原生进程，只绑回环地址）。
#
#   bash infra/scripts/start.sh              # MinIO + Keycloak
#   bash infra/scripts/start.sh minio
#   bash infra/scripts/start.sh keycloak
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"
LOGS="$BIN/logs"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

mkdir -p "$LOGS"

# 轮询健康端点直到服务就绪
wait_ready() {
  local label="$1" url="$2" tries="$3"
  for _ in $(seq 1 "$tries"); do
    if curl -sf -o /dev/null "$url"; then
      echo "$label 就绪 $url"
      return 0
    fi
    sleep 1
  done
  echo "$label 启动超时，见 $LOGS" >&2
  return 1
}

start_minio() {
  local api="${SM_MINIO_ENDPOINT:-http://127.0.0.1:9100}"
  local console="${SM_MINIO_CONSOLE:-http://127.0.0.1:9101}"
  local data="${SKILLMESH_DATA_DIR:-$HOME/.skillmesh-data}/minio"

  [ -f "$BIN/minio.exe" ] || bash "$ROOT/infra/scripts/prepare.sh" minio

  if curl -sf -o /dev/null "$api/minio/health/live"; then
    echo "MinIO 已在运行 $api"
    return 0
  fi

  mkdir -p "$data"
  MINIO_ROOT_USER="${SM_MINIO_ACCESS_KEY:-skillmesh}" \
  MINIO_ROOT_PASSWORD="${SM_MINIO_SECRET_KEY:-skillmesh-dev-secret}" \
  nohup "$BIN/minio.exe" server "$data" \
    --address "${api#http://}" --console-address "${console#http://}" \
    > "$LOGS/minio.log" 2>&1 &

  wait_ready "MinIO" "$api/minio/health/live" 30
}

start_keycloak() {
  local version="${KEYCLOAK_VERSION:-26.8.1}"
  local kc="$BIN/keycloak-${version}"
  local port="${SM_KEYCLOAK_PORT:-8180}"
  local issuer="${SM_KEYCLOAK_ISSUER:-http://127.0.0.1:${port}/kc/realms/skillmesh}"

  [ -d "$kc" ] || bash "$ROOT/infra/scripts/prepare.sh" keycloak

  mkdir -p "$kc/data/import"
  cp "$ROOT/infra/keycloak/realm-skillmesh.json" "$kc/data/import/"

  if curl -sf -o /dev/null "$issuer/.well-known/openid-configuration"; then
    echo "Keycloak 已在运行 $issuer"
    return 0
  fi

  export KC_BOOTSTRAP_ADMIN_USERNAME="${SM_KEYCLOAK_ADMIN_USER:-admin}"
  export KC_BOOTSTRAP_ADMIN_PASSWORD="${SM_KEYCLOAK_ADMIN_PASSWORD:-admin}"

  MSYS2_ARG_CONV_EXCL="*" nohup "$kc/bin/kc.bat" start-dev \
    --http-port="$port" --http-host=127.0.0.1 --http-relative-path=/kc --import-realm \
    > "$LOGS/keycloak.log" 2>&1 &

  wait_ready "Keycloak" "$issuer/.well-known/openid-configuration" 240
}

case "${1:-all}" in
  minio)    start_minio ;;
  keycloak) start_keycloak ;;
  all)      start_minio; start_keycloak ;;
  *)        echo "用法: $0 [minio|keycloak|all]" >&2; exit 2 ;;
esac
