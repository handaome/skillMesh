#!/usr/bin/env bash
# 停止本机服务进程。
#
#   bash infra/scripts/stop.sh              # MinIO + Keycloak
#   bash infra/scripts/stop.sh minio
#   bash infra/scripts/stop.sh keycloak
#
# 不按 pid 文件停：Windows 上 PID 会复用，存下来的 pid 可能已指向别的进程
# （实测 minio.pid 记的 pid 与真正在跑的 minio 对不上，kill -0 探不到就跳过了）。
# Keycloak 更是隔着一层 cmd.exe，杀了 cmd 带不走底下的 java。
# 改为按命令行特征找实际进程再停。
set -uo pipefail

stop_matching() {
  local label="$1" name="$2" pattern="$3"
  local pids pid found=0
  pids="$(powershell -NoProfile -Command "
    Get-CimInstance Win32_Process -Filter \"Name='$name'\" |
      Where-Object { \$_.CommandLine -like '*$pattern*' } |
      ForEach-Object { \$_.ProcessId }" 2>/dev/null | tr -d '\r')"
  for pid in $pids; do
    if taskkill //F //PID "$pid" >/dev/null 2>&1; then
      echo "已停止 $label (pid $pid)"
      found=1
    fi
  done
  [ "$found" = 1 ] || echo "$label 未在运行"
}

stop_minio()    { stop_matching "MinIO" "minio.exe" "minio.exe server"; }
stop_keycloak() { stop_matching "Keycloak" "java.exe" "keycloak-"; }

case "${1:-all}" in
  minio)    stop_minio ;;
  keycloak) stop_keycloak ;;
  all)      stop_minio; stop_keycloak ;;
  *)        echo "用法: $0 [minio|keycloak|all]" >&2; exit 2 ;;
esac
