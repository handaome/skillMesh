#!/usr/bin/env bash
# 迁移版本号检查。
#
# 规则（详见 docs/部署与排障.md「改 schema 的三种方式」）：
#   1. 文件名形如 V<版本>__<描述>.sql；
#   2. 同一个版本号不能有两个文件；
#   3. 新加的迁移不能用旧链用过的版本号——那些号在按旧链建过表的库里记着历史，
#      用上去就是 checksum mismatch。这一集从 git 历史里查，不写死在脚本里：
#      换一条链也照样对。
#   4. 新加的迁移要排在链尾：版本号比链上已有的都大。
#      插队的号会被先执行，后面的旧迁移再跑一遍，顺序就乱了。
#
# 用法：bash infra/scripts/check-migrations.sh
# 有问题就打印出来并以非 0 退出。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DIR="apps/api/src/main/resources/db/migrations"
ABS="$ROOT/$DIR"

if [ ! -d "$ABS" ]; then
  echo "找不到迁移目录：$ABS" >&2
  exit 2
fi

status=0

# 链上的版本号与文件名
declare -A seen=()
versions=()
while IFS= read -r file; do
  base="$(basename "$file")"
  if [[ ! "$base" =~ ^V([0-9]+)__[A-Za-z0-9_]+\.sql$ ]]; then
    echo "命名不对：$base（要形如 V13__drop_scanning_status.sql）" >&2
    status=1
    continue
  fi
  version="${BASH_REMATCH[1]}"
  if [ -n "${seen[$version]:-}" ]; then
    echo "版本号重复：V$version（${seen[$version]} 与 $base）" >&2
    status=1
  fi
  seen[$version]="$base"
  versions+=("$version")
done < <(find "$ABS" -maxdepth 1 -name 'V*.sql' | sort)

if [ ${#versions[@]} -eq 0 ]; then
  echo "迁移目录里没有迁移文件" >&2
  exit 2
fi

# 旧链用过的版本号：从 git 历史里查（被删掉的那些迁移文件）。
legacy=$(git -C "$ROOT" log --diff-filter=D --name-only --pretty=format: -- "$DIR/V*.sql" 2>/dev/null \
  | sed -n 's|.*/V\([0-9][0-9]*\)__.*|\1|p' | sort -n -u)

# 已提交的链（HEAD 上的版本号）：新迁移只能排在它后面。
# 一次加好几条迁移时（V15、V16、V17 一起提交），新迁移之间不算插队——
# 插队的意思是「插到已经跑过的迁移前面」，那要看的是已提交的那条链。
committed=$(git -C "$ROOT" ls-tree -r --name-only HEAD -- "$DIR" 2>/dev/null \
  | sed -n 's|.*/V\([0-9][0-9]*\)__.*|\1|p')

# 新加的迁移（相对 HEAD 是新增文件，或还没提交的新文件）：
# 既不能用旧号，也不能插队。
while IFS= read -r path; do
  [ -n "$path" ] || continue
  base="$(basename "$path")"
  [[ "$base" =~ ^V([0-9]+)__ ]] || continue
  version="${BASH_REMATCH[1]}"
  if printf '%s\n' $legacy | grep -qx "$version"; then
    echo "V$version 旧链用过（$base）：在按旧链建过表的库上用上去就是 checksum mismatch，换个号" >&2
    status=1
  fi
  for other in $committed; do
    if [ "$other" -gt "$version" ]; then
      echo "V$version 插队了：链上已经有 V$other，新迁移要排在链尾" >&2
      status=1
    fi
  done
done < <(
  {
    git -C "$ROOT" diff --name-only --diff-filter=A HEAD -- "$DIR/V*.sql"
    git -C "$ROOT" ls-files --others --exclude-standard -- "$DIR/V*.sql"
  } 2>/dev/null
)

# 旧链用过、当前链没用的号：留着当提示——它们是「跳过的空洞」，不是错误。
skipped=""
while IFS= read -r version; do
  [ -n "$version" ] || continue
  [ -n "${seen[$version]:-}" ] && continue
  skipped="$skipped $version"
done < <(printf '%s\n' $legacy)

max=$(printf '%s\n' "${versions[@]}" | sort -n | tail -1)
echo "迁移链：$(printf '%s\n' "${versions[@]}" | sort -n | tr '\n' ' ')（${#versions[@]} 条）"
echo "下一个可用版本号：V$((max + 1))"
if [ -n "$skipped" ]; then
  echo "旧链用过、当前链跳过（空洞，别再往里填）：$(echo $skipped | tr ' ' '\n' | sort -n | tr '\n' ' ')"
fi

exit "$status"
