#!/usr/bin/env bash
# 导出一份备份（数据库表 + 对象存储）。
#
#   bash infra/scripts/backup.sh [备份目录]
#
# 默认目录：$HOME/.skillmesh-data/backup
#
# 走应用自己的备份器（BackupTool），不用 pg_dump / mc——
# 那两个客户端每台机器上都要装，而这两个 SDK 本来就在依赖里。
#
# 应用起不来的时候也能跑：BackupTool 绕开 Flyway 校验
# （迁移历史对不上时应用根本起不来，那恰恰是最需要备份的时候）。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_DIR="$ROOT/apps/api"
TARGET="${1:-$HOME/.skillmesh-data/backup}"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

cd "$API_DIR"

if [ ! -d target/classes ]; then
  echo "先编译：cd apps/api && ./mvnw -o compile" >&2
  exit 1
fi

M2="${HOME}/.m2/repository"

# Spring Boot 版本从 pom 里读——.m2 里装着别的项目用的新版本（4.x），
# 挑错会加载不兼容的类。
BOOT_VERSION=$(sed -n '/<artifactId>spring-boot-starter-parent<\/artifactId>/{n;s/.*<version>\(.*\)<\/version>.*/\1/p;}' pom.xml | head -1)
if [ -z "$BOOT_VERSION" ]; then
  echo "读不到 pom 里的 spring-boot 版本" >&2
  exit 1
fi
# Spring Boot 3.5.x 对应 Spring Framework 6.2.x；3.4.x 是 6.1.x；3.3.x 是 6.0.x。
case "$BOOT_VERSION" in
  3.5.*) FW_VERSION="6.2" ;;
  3.4.*) FW_VERSION="6.1" ;;
  3.3.*) FW_VERSION="6.0" ;;
  *) FW_VERSION="6.2" ;;
esac

# pick 从 .m2 里挑一个 jar：$1 是 artifact 目录（如 .../org/springframework/spring-core），
# $2 是版本段（可空）。目录形如 <artifact>/<version>/<artifact>-<version>.jar。
# 版本段单独传，这样只挑到对的那个大版本——
# 同一个库在 .m2 里有多个大版本并存（slf4j 1.7 与 2.0、spring-boot 3.5 与 4.1），
# 混进来会加载错的那个。
CP="target/classes"
pick() {
  local dir="$1" version="$2" jar
  local name
  name=$(basename "$dir")
  [ -d "$dir" ] || return 0
  if [ -n "$version" ]; then
    jar=$(find "$dir" -mindepth 2 -maxdepth 2 -name "${name}-${version}*.jar" \
          ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort -V | tail -1)
  else
    jar=$(find "$dir" -mindepth 2 -maxdepth 2 -name "${name}-*.jar" \
          ! -name "*sources*" ! -name "*javadoc*" 2>/dev/null | sort -V | tail -1)
  fi
  if [ -n "$jar" ]; then
    CP="$CP;$(cygpath -w "$jar" 2>/dev/null || echo "$jar")"
  fi
}

pick "$M2/org/springframework/boot/spring-boot" "$BOOT_VERSION"
pick "$M2/org/springframework/boot/spring-boot-autoconfigure" "$BOOT_VERSION"
pick "$M2/org/springframework/spring-core" "$FW_VERSION"
pick "$M2/org/springframework/spring-jdbc" "$FW_VERSION"
pick "$M2/org/springframework/spring-tx" "$FW_VERSION"
pick "$M2/org/springframework/spring-beans" "$FW_VERSION"
pick "$M2/org/springframework/spring-context" "$FW_VERSION"
pick "$M2/org/springframework/spring-expression" "$FW_VERSION"
pick "$M2/org/springframework/spring-aop" "$FW_VERSION"
pick "$M2/org/springframework/spring-jcl" "$FW_VERSION"
pick "$M2/jakarta/annotation/jakarta.annotation-api" ""
pick "$M2/com/fasterxml/jackson/core/jackson-databind" ""
pick "$M2/com/fasterxml/jackson/core/jackson-core" ""
pick "$M2/com/fasterxml/jackson/core/jackson-annotations" ""
pick "$M2/io/minio/minio" ""
pick "$M2/org/slf4j/slf4j-api" "2."
pick "$M2/org/yaml/snakeyaml" ""
pick "$M2/ch/qos/logback/logback-classic" ""
pick "$M2/ch/qos/logback/logback-core" ""

echo "导出到 $TARGET ..."
java -cp "$CP" com.skillmesh.backup.BackupTool "$TARGET"
