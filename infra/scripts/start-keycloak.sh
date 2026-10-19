#!/usr/bin/env bash
# 启动本机 Keycloak（开发模式 + 导入 realm）。只绑回环地址。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"
LOGS="$BIN/logs"
VERSION="${KEYCLOAK_VERSION:-26.8.1}"
KC="$BIN/keycloak-${VERSION}"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

PORT="${SM_KEYCLOAK_PORT:-8180}"
ISSUER="${SM_KEYCLOAK_ISSUER:-http://127.0.0.1:${PORT}/kc/realms/skillmesh}"

mkdir -p "$LOGS"

[ -d "$KC" ] || bash "$ROOT/infra/scripts/fetch-keycloak.sh"

# --import-realm 从 <KEYCLOAK_HOME>/data/import 读取 realm 定义
mkdir -p "$KC/data/import"
cp "$ROOT/infra/keycloak/realm-skillmesh.json" "$KC/data/import/"

if curl -sf -o /dev/null "$ISSUER/.well-known/openid-configuration"; then
  echo "Keycloak 已在运行 $ISSUER"
  exit 0
fi

export KC_BOOTSTRAP_ADMIN_USERNAME="${SM_KEYCLOAK_ADMIN_USER:-admin}"
export KC_BOOTSTRAP_ADMIN_PASSWORD="${SM_KEYCLOAK_ADMIN_PASSWORD:-admin}"

nohup "$KC/bin/kc.bat" start-dev \
  --http-port="$PORT" --http-host=127.0.0.1 --http-relative-path=/kc --import-realm \
  > "$LOGS/keycloak.log" 2>&1 &
echo $! > "$LOGS/keycloak.pid"

for _ in $(seq 1 120); do
  if curl -sf -o /dev/null "$ISSUER/.well-known/openid-configuration"; then
    echo "Keycloak 就绪 $ISSUER"
    exit 0
  fi
  sleep 2
done

echo "Keycloak 启动超时，见 $LOGS/keycloak.log" >&2
exit 1
