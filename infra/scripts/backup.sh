#!/usr/bin/env bash
# 导出一份备份（数据库表 + 对象存储），或把一份备份恢复回去。
#
#   bash infra/scripts/backup.sh [备份目录]
#   bash infra/scripts/backup.sh restore <备份目录> [schema]
#
# 默认目录：$HOME/.skillmesh-data/backup
#
# 恢复要指到某一次备份的目录（<备份根目录>/<时间戳>/），
# 目标库得是空的——恢复是往空库里灌，有数据会直接拒绝。
#
# 走应用自己的备份器（BackupTool），不用 pg_dump / mc——
# 那两个客户端每台机器上都要装，而这两个 SDK 本来就在依赖里。
#
# 应用起不来的时候也能跑：BackupTool 绕开 Flyway 校验
# （迁移历史对不上时应用根本起不来，那恰恰是最需要备份的时候）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_DIR="$ROOT/apps/api"

MODE="export"
if [ "${1:-}" = "restore" ]; then
  MODE="restore"
  shift
fi
TARGET="${1:-$HOME/.skillmesh-data/backup}"
SCHEMA="${2:-public}"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

cd "$API_DIR"

if [ ! -d target/classes ]; then
  echo "先编译：cd apps/api && ./mvnw -o compile" >&2
  echo "（仓库不在默认位置时，先 export MAVEN_REPO=<仓库路径>）" >&2
  exit 1
fi

# Maven 仓库位置：优先 MAVEN_REPO 环境变量（本脚本会加载根目录 .env，写在那里即可），
# 其次默认 ~/.m2/repository。
M2="${MAVEN_REPO:-}"
[ -n "$M2" ] || M2="$HOME/.m2/repository"

# shellcheck source=lib/toolkit.sh
. "$ROOT/infra/scripts/lib/toolkit.sh"

# 编译产物是 pom 里 <java.version> 那个版本编出来的，用低版本 JRE 跑只会抛
# UnsupportedClassVersionError——先挑 JDK，版本不够就直说。
JAVA_BIN=$(tool_pick_java "$(tool_required_java pom.xml)")
CP=$(tool_runtime_classpath "$M2")

if [ "$MODE" = "restore" ]; then
  echo "从 $TARGET 恢复到 schema $SCHEMA ..."
  "$JAVA_BIN" -cp "$CP" com.skillmesh.backup.BackupTool restore "$TARGET" "$SCHEMA"
else
  echo "导出到 $TARGET ..."
  "$JAVA_BIN" -cp "$CP" com.skillmesh.backup.BackupTool "$TARGET"
fi
