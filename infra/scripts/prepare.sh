#!/usr/bin/env bash
# 准备本机要用的二进制依赖：MinIO（源码构建）。
#
#   bash infra/scripts/prepare.sh            # 准备
#   bash infra/scripts/prepare.sh minio
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BIN="$ROOT/infra/bin"

# .env 里的 SKILLMESH_GO_BIN、GOPROXY 等在这里也要生效（直接调用本脚本时）
set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

# MinIO 官方社区版二进制已于 2025-10 下架。
prepare_minio() {
  local work="${TMPDIR:-/tmp}/skillmesh-minio-build"
  local version="v0.0.0-20260212201848-7aac2a2c5b7c"

  if [ -f "$BIN/minio.exe" ]; then
    echo "minio.exe 已存在，跳过构建"
    "$BIN/minio.exe" --version
    return 0
  fi

  # 选择 Go 工具链：SKILLMESH_GO_BIN 优先，其次 PATH，最后 Windows 常见安装位置。
  # 32 位工具链链 amd64 大二进制会 OOM（mapping output file failed: Not enough memory）。
  local go_bin="${SKILLMESH_GO_BIN:-go}"
  if ! command -v "$go_bin" >/dev/null 2>&1; then
    for candidate in "C:/Program Files/Go/bin/go.exe" "C:/Program Files (x86)/Go/bin/go.exe"; do
      if [ -x "$candidate" ]; then go_bin="$candidate"; break; fi
    done
  fi
  if ! command -v "$go_bin" >/dev/null 2>&1 && [ ! -x "$go_bin" ]; then
    echo "找不到 Go 工具链：在 .env 里设置 SKILLMESH_GO_BIN 指向 go.exe" >&2
    return 1
  fi
  # 系统里可能预置了指向别的工具链的 GOROOT，不显式对齐会报 "no such tool compile"
  local go_bin_path goroot_dir
  go_bin_path="$(command -v "$go_bin" || echo "$go_bin")"
  goroot_dir="$(cd "$(dirname "$go_bin_path")/.." && pwd)"
  # Git Bash 下 pwd 是 /c/... 形式，Go 识别不了，转成 Windows 路径
  if command -v cygpath >/dev/null 2>&1; then
    goroot_dir="$(cygpath -m "$goroot_dir")"
  fi
  export GOROOT="$goroot_dir"
  # 直连 proxy.golang.org / sum.golang.org 在国内常超时，统一走 goproxy.cn
  # （goproxy.cn 同时代理 sumdb，校验不会再去连 sum.golang.org）
  export GOPROXY="${GOPROXY:-https://goproxy.cn,direct}"

  mkdir -p "$BIN" "$work"
  cd "$work"
  [ -f go.mod ] || "$go_bin" mod init skillmesh/minio-build
  GOFLAGS=-mod=mod "$go_bin" get "github.com/minio/minio@$version"
  GOARCH=amd64 "$go_bin" build -o "$BIN/minio.exe" github.com/minio/minio

  "$BIN/minio.exe" --version
  echo "MinIO 已构建到 $BIN/minio.exe"
}

case "${1:-all}" in
  minio)    prepare_minio ;;
  all)      prepare_minio ;;
  *)        echo "用法: $0 [minio|all]" >&2; exit 2 ;;
esac
