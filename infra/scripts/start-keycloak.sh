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

# Git Bash 下直接跑 .bat 有两个坑：
#   1) POSIX 路径会被 mangle，kc.home.dir 变成 "C:\Program Files\Git\..."
#   2) 形如 /kc 的参数会被 MSYS 当成 Unix 路径转成 "C:\Program Files\Git\kc"
# 两者都让 Keycloak 报 "Specified path is an invalid URI"。
# 解法：把命令写进 .cmd 包装器，参数不再经过 MSYS 转换。
export SKILLMESH_KC_HOME="$(cygpath -w "$KC" 2>/dev/null || echo "$KC")"
export SKILLMESH_KC_PORT="$PORT"
export SKILLMESH_KC_HOST="127.0.0.1"
LAUNCHER_WIN="$(cygpath -w "$ROOT/infra/scripts/keycloak-start.cmd" 2>/dev/null || echo "$ROOT/infra/scripts/keycloak-start.cmd")"

nohup cmd.exe //c "$LAUNCHER_WIN" \
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
