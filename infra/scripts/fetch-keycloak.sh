#!/usr/bin/env bash
# 从 Maven Central 取 Keycloak 发行包。
# 本机 GitHub 不可达，Keycloak 的 release 资产在 GitHub 上拿不到，
# 但 org.keycloak:keycloak-quarkus-dist 发布了同样的 zip。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"
VERSION="${KEYCLOAK_VERSION:-26.8.1}"
URL="https://repo1.maven.org/maven2/org/keycloak/keycloak-quarkus-dist/${VERSION}/keycloak-quarkus-dist-${VERSION}.zip"
ZIP="$BIN/keycloak-${VERSION}.zip"

mkdir -p "$BIN"

if [ -d "$BIN/keycloak-${VERSION}" ]; then
  echo "Keycloak ${VERSION} 已存在，跳过下载"
  exit 0
fi

[ -f "$ZIP" ] || curl -fSL --retry 3 --max-time 600 -o "$ZIP" "$URL"
unzip -q -o "$ZIP" -d "$BIN"
rm -f "$ZIP"

test -f "$BIN/keycloak-${VERSION}/bin/kc.bat"
echo "Keycloak ${VERSION} 就绪：$BIN/keycloak-${VERSION}"
