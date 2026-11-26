#!/usr/bin/env bash
# 独立工具脚本（backup.sh / reset-password.sh）共用的两件事：
#
#   1. 挑一个跑得动编译产物的 JDK。编译目标是 pom 里的 <java.version>，
#      拿低版本 JRE 去跑只会得到 UnsupportedClassVersionError，看不出是 JDK 太老。
#   2. 解析运行期 classpath。交给 Maven 算，版本跟着 pom 走——
#      手挑 jar 容易挑到别的大版本（.m2 里常驻着多个大版本），加载错类时症状还很难懂。
#
# 被 source 进来，不单独执行。
#
# 下面的取值一律用 grep/tr 拼，不用带引号的 sed 替换：
# Git Bash（MSYS）会把带冒号的参数当路径列表改写，sed 的表达式会被改坏。

# pom 里 <java.version> 要求的 JDK 主版本。读不到就返回空，由调用方报错。
tool_required_java() {
  grep -m1 -o 'java.version>[0-9][0-9]*' "$1" | head -1 | tr -dc '0-9'
}

# JAVA_HOME 里的 java。Windows 上 JAVA_HOME 写作 D:\dev\jdk25，
# Git Bash 里得先转一道才是能执行的路径。
tool_java_home() {
  local home="${JAVA_HOME:-}" candidate
  [ -n "$home" ] || return 0
  candidate="$home/bin/java"
  if [ ! -x "$candidate" ] && command -v cygpath >/dev/null 2>&1; then
    candidate="$(cygpath -u "$home" 2>/dev/null)/bin/java"
  fi
  [ -x "$candidate" ] && printf '%s\n' "$candidate"
}

# java 的主版本号（21 → 21，25.0.2 → 25）。
tool_java_major() {
  "$1" -version 2>&1 | grep -m1 -o 'version "[0-9][0-9]*' | tr -dc '0-9'
}

# 挑 java：先 JAVA_HOME，再 PATH 上的。都不够新就把要求说清楚，别让调用方猜。
tool_pick_java() {
  local required="${1:-}" candidate major tried=""
  if [ -z "$required" ]; then
    echo "读不到 pom 里的 java.version，不知道该用哪个 JDK" >&2
    return 1
  fi
  for candidate in "$(tool_java_home)" "java"; do
    [ -n "$candidate" ] || continue
    major="$(tool_java_major "$candidate" || true)"
    if [ -n "$major" ] && [ "$major" -ge "$required" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
    tried="$tried
  $candidate -> ${major:-读不到版本}"
  done
  echo "需要 JDK $required 及以上（编译目标就是它），这台机器上的 java 都太旧：$tried" >&2
  echo "把 JAVA_HOME 指到 JDK $required 再跑" >&2
  return 1
}

# classpath 分隔符：Windows 用 ;，其余平台用 :
tool_path_sep() {
  case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) printf ';' ;;
    *) printf ':' ;;
  esac
}

# 运行期 classpath（可直接给 java -cp）。要在 apps/api 目录里调用。
# $1：可选的 Maven 仓库位置（对应 MAVEN_REPO）。
tool_runtime_classpath() {
  local repo="${1:-}" cp_file="target/runtime-classpath.txt" repo_args=()
  if [ -n "$repo" ]; then
    repo_args=(-Dmaven.repo.local="$repo")
  fi
  if [ ! -f "$cp_file" ] || [ pom.xml -nt "$cp_file" ]; then
    # 离线优先：插件自己的依赖未必缓存过，缺了就联网取一次（取过之后一直能离线跑）。
    if ! ./mvnw -o -q dependency:build-classpath ${repo_args[@]+"${repo_args[@]}"} \
         -Dmdep.outputFile="$cp_file" -DincludeScope=runtime > "$cp_file.log" 2>&1; then
      ./mvnw -q dependency:build-classpath ${repo_args[@]+"${repo_args[@]}"} \
        -Dmdep.outputFile="$cp_file" -DincludeScope=runtime
    fi
    rm -f "$cp_file.log"
  fi
  printf 'target/classes%s%s\n' "$(tool_path_sep)" "$(cat "$cp_file")"
}
