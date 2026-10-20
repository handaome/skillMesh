#!/usr/bin/env bash
# 从源码构建 MinIO 服务端。
# 官方社区版二进制已于 2025-10 下架（dl.min.io 全部 410 Gone），只能源码构建。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"
WORK="${TMPDIR:-/tmp}/skillmesh-minio-build"
VERSION="v0.0.0-20260212201848-7aac2a2c5b7c"

# .env 里有 SKILLMESH_GO_BIN（指向 windows-amd64 版 Go）。
# 不加载它就会用系统的 32 位 Go，linker 链大二进制时 OOM。
set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

# 选择 Go 工具链。32 位工具链的 linker 链 amd64 大二进制会 OOM
# （mapping output file failed: Not enough memory），因此用 SKILLMESH_GO_BIN
# 指向一个 windows-amd64 版 Go。
GO_BIN="${SKILLMESH_GO_BIN:-go}"
GO_BIN_PATH="$(command -v "$GO_BIN" || echo "$GO_BIN")"
# 系统里可能预置了指向别的工具链的 GOROOT，不显式对齐会报 "no such tool compile"
export GOROOT="$(cd "$(dirname "$GO_BIN_PATH")/.." && pwd)"

mkdir -p "$BIN" "$WORK"

if [ -f "$BIN/minio.exe" ]; then
  echo "minio.exe 已存在，跳过构建"
  "$BIN/minio.exe" --version
  exit 0
fi

cd "$WORK"
[ -f go.mod ] || "$GO_BIN" mod init skillmesh/minio-build
GOFLAGS=-mod=mod "$GO_BIN" get "github.com/minio/minio@$VERSION"
GOARCH=amd64 "$GO_BIN" build -o "$BIN/minio.exe" github.com/minio/minio

"$BIN/minio.exe" --version
echo "MinIO 已构建到 $BIN/minio.exe"
