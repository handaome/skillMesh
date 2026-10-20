#!/usr/bin/env bash
# 补建第二个测试用户 dev-user2。
#
# Keycloak 的 realm 导入不覆盖已存在的用户——改了 realm-skillmesh.json
# 之后，已经跑过的本机实例里还是没有这个用户。这个脚本用 kcadm 直接建。
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KC="$ROOT/infra/bin/keycloak-26.8.1"
ADMIN_USER="${SM_KEYCLOAK_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${SM_KEYCLOAK_ADMIN_PASSWORD:-admin}"
ISSUER="http://127.0.0.1:8180/kc"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a
ADMIN_USER="${SM_KEYCLOAK_ADMIN_USER:-$ADMIN_USER}"
ADMIN_PASSWORD="${SM_KEYCLOAK_ADMIN_PASSWORD:-$ADMIN_PASSWORD}"

if [ ! -x "$KC/bin/kcadm.sh" ] && [ ! -f "$KC/bin/kcadm.sh" ]; then
  echo "找不到 kcadm.sh，先跑 fetch-keycloak.sh" >&2
  exit 1
fi

"$KC/bin/kcadm.sh" config credentials --server "$ISSUER" --realm master \
  --user "$ADMIN_USER" --password "$ADMIN_PASSWORD" >/dev/null

if "$KC/bin/kcadm.sh" get users -r skillmesh -q username=dev-user2 \
    --fields username --format csv 2>/dev/null | grep -q dev-user2; then
  echo "dev-user2 已存在"
  exit 0
fi

USER_ID=$("$KC/bin/kcadm.sh" create users -r skillmesh \
  -s username=dev-user2 -s enabled=true -s email=dev2@skillmesh.local \
  -s emailVerified=true -s firstName=Dev -s lastName="User Two" -i)
"$KC/bin/kcadm.sh" set-password -r skillmesh --userid "$USER_ID" \
  --new-password dev2 --temporary=false >/dev/null
"$KC/bin/kcadm.sh" add-roles -r skillmesh --uusername dev-user2 \
  --rolename skillmesh-user >/dev/null
echo "dev-user2 已创建（密码 dev2）"
