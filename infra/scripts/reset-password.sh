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

cd "$API_DIR"

if [ ! -d target/classes ]; then
  echo "先编译：cd apps/api && ./mvnw -o compile" >&2
  exit 1
fi

# classpath 交给 Maven 解析：版本跟着 pom 走，不用手挑 jar
# （手挑容易挑到别的大版本，加载错类，症状还很难看懂）。
CP_FILE="target/runtime-classpath.txt"
if [ ! -f "$CP_FILE" ] || [ pom.xml -nt "$CP_FILE" ]; then
  # 离线优先：插件自己的依赖未必缓存过，缺了就联网取一次（取过之后一直能离线跑）。
  if ! ./mvnw -o -q dependency:build-classpath \
       -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime > "$CP_FILE.log" 2>&1; then
    ./mvnw -q dependency:build-classpath \
      -Dmdep.outputFile="$CP_FILE" -DincludeScope=runtime
  fi
  rm -f "$CP_FILE.log"
fi

# classpath 分隔符：Windows 用 ;，其余平台用 :
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) SEP=';' ;;
  *) SEP=':' ;;
esac

exec java -cp "target/classes$SEP$(cat "$CP_FILE")" com.skillmesh.admin.PasswordTool "$@"
