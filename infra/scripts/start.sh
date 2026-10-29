#!/usr/bin/env bash
# 启动本机服务（原生进程，只绑回环地址）。
#
#   bash infra/scripts/start.sh              # MinIO
#   bash infra/scripts/start.sh minio
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

case "${1:-all}" in
  minio)    start_minio ;;
  all)      start_minio ;;
  *)        echo "用法: $0 [minio|all]" >&2; exit 2 ;;
esac
