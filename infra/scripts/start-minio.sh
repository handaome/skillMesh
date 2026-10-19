#!/usr/bin/env bash
# 启动本机 MinIO。只绑回环地址，不监听 0.0.0.0。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"
LOGS="$BIN/logs"
DATA="${SKILLMESH_DATA_DIR:-$HOME/.skillmesh-data}/minio"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

API="${SM_MINIO_ENDPOINT:-http://127.0.0.1:9100}"
API_HOSTPORT="${API#http://}"
CONSOLE_HOSTPORT="${SM_MINIO_CONSOLE:-http://127.0.0.1:9101}"
CONSOLE_HOSTPORT="${CONSOLE_HOSTPORT#http://}"

mkdir -p "$LOGS" "$DATA"

[ -f "$BIN/minio.exe" ] || bash "$ROOT/infra/scripts/build-minio.sh"

if curl -sf -o /dev/null "$API/minio/health/live"; then
  echo "MinIO 已在运行 $API"
  exit 0
fi

MINIO_ROOT_USER="${SM_MINIO_ACCESS_KEY:-skillmesh}" \
MINIO_ROOT_PASSWORD="${SM_MINIO_SECRET_KEY:-skillmesh-dev-secret}" \
nohup "$BIN/minio.exe" server "$DATA" \
  --address "$API_HOSTPORT" --console-address "$CONSOLE_HOSTPORT" \
  > "$LOGS/minio.log" 2>&1 &
echo $! > "$LOGS/minio.pid"

for _ in $(seq 1 30); do
  if curl -sf -o /dev/null "$API/minio/health/live"; then
    echo "MinIO 就绪 $API（控制台 $CONSOLE_HOSTPORT）"
    exit 0
  fi
  sleep 1
done

echo "MinIO 启动超时，见 $LOGS/minio.log" >&2
exit 1
