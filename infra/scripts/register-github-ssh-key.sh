#!/usr/bin/env bash
# 把本机的 SSH 公钥注册到 GitHub 账号。
#
# 用途：本机到 github.com 的 443 不通，但 api.github.com 通。
# 注册了 SSH 公钥后，git clone 走 22 端口就能用，不依赖代理。
#
# 前提：一个带 admin:public_key 权限的 Personal Access Token
# （fine-grained token 需要 "Administration: SSH public keys" 写权限）。
# 令牌只从环境变量读，不落盘、不进 shell 历史。
#
#   export GITHUB_TOKEN=ghp_xxx
#   bash infra/scripts/register-github-ssh-key.sh
set -euo pipefail

KEY_FILE="${1:-$HOME/.ssh/id_ed25519.pub}"
API="https://api.github.com"

if [ -z "${GITHUB_TOKEN:-}" ]; then
  echo "缺少 GITHUB_TOKEN 环境变量" >&2
  echo "  export GITHUB_TOKEN=<你的 PAT>" >&2
  exit 1
fi

if [ ! -f "$KEY_FILE" ]; then
  echo "公钥文件不存在: $KEY_FILE" >&2
  echo "  先执行: ssh-keygen -t ed25519 -C skillmesh-dev -f ~/.ssh/id_ed25519 -N \"\"" >&2
  exit 1
fi

PUBKEY="$(cat "$KEY_FILE")"
TITLE="${GITHUB_SSH_KEY_TITLE:-skillmesh-dev}"

echo "注册公钥到 GitHub 账号..."
response="$(curl -sS -w '\n%{http_code}' \
  -X POST "$API/user/keys" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  -d "$(printf '{"title":"%s","key":"%s"}' "$TITLE" "$PUBKEY")")"

status="$(echo "$response" | tail -n1)"
body="$(echo "$response" | sed '$d')"

case "$status" in
  201)
    echo "已注册。"
    ;;
  422)
    if echo "$body" | grep -q "already_exists"; then
      echo "该公钥已注册过，无需重复添加。"
    else
      echo "注册失败（HTTP $status）：" >&2
      echo "$body" >&2
      exit 1
    fi
    ;;
  *)
    echo "注册失败（HTTP $status）：" >&2
    echo "$body" >&2
    exit 1
    ;;
esac

echo
echo "账号当前的 SSH 公钥："
curl -sS "$API/user/keys" \
  -H "Authorization: Bearer $GITHUB_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "X-GitHub-Api-Version: 2022-11-28" \
  | python -c "
import json, sys
for key in json.load(sys.stdin):
    print('  %-24s %s...%s' % (key['title'], key['key'][:20], key['key'][-8:]))
"
