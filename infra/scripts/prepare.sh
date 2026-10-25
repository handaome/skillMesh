#!/usr/bin/env bash
# 准备本机要用的二进制依赖：MinIO（源码构建）与 Keycloak（Maven Central 下载）。
#
#   bash infra/scripts/prepare.sh            # 两个都准备
#   bash infra/scripts/prepare.sh minio
#   bash infra/scripts/prepare.sh keycloak
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"

# MinIO 官方社区版二进制已于 2025-10 下架。
prepare_minio() {
  local work="${TMPDIR:-/tmp}/skillmesh-minio-build"
  local version="v0.0.0-20260212201848-7aac2a2c5b7c"

  if [ -f "$BIN/minio.exe" ]; then
    echo "minio.exe 已存在，跳过构建"
    "$BIN/minio.exe" --version
    return 0
  fi

  mkdir -p "$BIN" "$work"
  cd "$work"
  [ -f go.mod ] || go mod init skillmesh/minio-build
  GOFLAGS=-mod=mod go get "github.com/minio/minio@$version"
  GOARCH=amd64 go build -o "$BIN/minio.exe" github.com/minio/minio

  "$BIN/minio.exe" --version
  echo "MinIO 已构建到 $BIN/minio.exe"
}

prepare_keycloak() {
  local version="${KEYCLOAK_VERSION:-26.8.1}"
  local url="https://repo1.maven.org/maven2/org/keycloak/keycloak-quarkus-dist/${version}/keycloak-quarkus-dist-${version}.zip"
  local zip="$BIN/keycloak-${version}.zip"

  if [ -d "$BIN/keycloak-${version}" ]; then
    echo "Keycloak ${version} 已存在，跳过下载"
    return 0
  fi

  mkdir -p "$BIN"
  [ -f "$zip" ] || curl -fSL --retry 3 --max-time 600 -o "$zip" "$url"
  unzip -q -o "$zip" -d "$BIN"
  rm -f "$zip"

  test -f "$BIN/keycloak-${version}/bin/kc.bat"
  echo "Keycloak ${version} 就绪：$BIN/keycloak-${version}"
}

case "${1:-all}" in
  minio)    prepare_minio ;;
  keycloak) prepare_keycloak ;;
  all)      prepare_minio; prepare_keycloak ;;
  *)        echo "用法: $0 [minio|keycloak|all]" >&2; exit 2 ;;
esac
