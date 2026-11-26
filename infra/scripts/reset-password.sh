#!/usr/bin/env bash
# 重置内置账号的密码——管理员密码忘了时的急救通道。
#
#   bash infra/scripts/reset-password.sh list
#   bash infra/scripts/reset-password.sh reset <用户名>            # 交互输入新密码
#   bash infra/scripts/reset-password.sh reset <用户名> --generate # 随机生成一个并写到本地文件
#   bash infra/scripts/reset-password.sh reset <用户名> --enable   # 顺便把 disabled 的账号启用
#
# 直接改库里的密码哈希，不启动应用：应用起不来的时候恰恰是最需要它的时候
# （密码忘了就登不进去，迁移历史对不上也起不来）。
# 前提是这台机器能连上数据库——凭据读根目录的 .env（应用自己的配置也读它）。
#
# 走应用里的 PasswordTool：哈希算法、密码长度规则都跟注册那条路一致，
# 不会出现「工具改的密码登录不了」。
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
API_DIR="$ROOT/apps/api"

set -a
[ -f "$ROOT/.env" ] && . "$ROOT/.env"
set +a

cd "$API_DIR"

if [ ! -d target/classes ]; then
  echo "先编译：cd apps/api && ./mvnw -o compile" >&2
  exit 1
fi

# classpath 交给 Maven 解析：版本跟着 pom 走，不用手挑 jar
# （手挑容易挑到别的大版本，加载错类，症状还很难看懂）。
# shellcheck source=lib/toolkit.sh
. "$ROOT/infra/scripts/lib/toolkit.sh"

# Maven 仓库位置：优先 MAVEN_REPO（写在根目录 .env 里），其次默认 ~/.m2/repository。
M2="${MAVEN_REPO:-}"
[ -n "$M2" ] || M2="$HOME/.m2/repository"

# 编译产物是 pom 里 <java.version> 那个版本编出来的，用低版本 JRE 跑只会抛
# UnsupportedClassVersionError——先挑 JDK，版本不够就直说。
JAVA_BIN=$(tool_pick_java "$(tool_required_java pom.xml)")
CP=$(tool_runtime_classpath "$M2")

exec "$JAVA_BIN" -cp "$CP" com.skillmesh.admin.PasswordTool "$@"
