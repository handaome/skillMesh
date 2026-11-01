#!/usr/bin/env bash
# 签发 / 续期 / 查看 TLS 证书。
#
# 用 certbot 的 webroot 模式：HTTP-01 校验走 80 端口，
# webroot 目录是 infra/acme（compose 里挂进 nginx 的 /var/www/certbot）。
# 证书落在 /etc/letsencrypt/live/<域名>/，再挂进 nginx 容器。
#
# 用法：
#   bash infra/scripts/tls-cert.sh issue -d skills.example.com [-d alt.example.com] [-m ops@example.com]
#   bash infra/scripts/tls-cert.sh renew
#   bash infra/scripts/tls-cert.sh check -d skills.example.com
#
# 前提：
#   1. 域名已经解析到这台机器，80 端口对外通着；
#   2. compose 的 web 服务把 ./infra/acme 挂到 /var/www/certbot；
#   3. 机器上装了 certbot（apt install certbot）。
#
# 续期：certbot 只在证书快到期时才真的续，续完会跑部署钩子 reload nginx——
# 不 reload 的话 nginx 还在用旧证书，续了等于没续。
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WEBROOT="$ROOT/infra/acme"
COMPOSE="$ROOT/docker-compose.yml"
EMAIL=""
DOMAINS=()
COMMAND="${1:-issue}"
if [ $# -gt 0 ]; then
  shift
fi

usage() {
  cat >&2 <<'EOF'
用法：
  tls-cert.sh issue -d <域名> [-d <另一个域名>] [-m <邮箱>]
  tls-cert.sh renew
  tls-cert.sh check -d <域名>
EOF
  exit 2
}

while [ $# -gt 0 ]; do
  case "$1" in
    -d|--domain)
      DOMAINS+=("${2:-}")
      shift 2
      ;;
    -m|--email)
      EMAIL="${2:-}"
      shift 2
      ;;
    -h|--help)
      usage
      ;;
    *)
      echo "不认识的参数：$1" >&2
      usage
      ;;
  esac
done

need_certbot() {
  if ! command -v certbot >/dev/null 2>&1; then
    echo "没装 certbot：apt install certbot（Debian/Ubuntu）" >&2
    exit 2
  fi
}

# 续期成功后要 reload nginx，否则新证书不生效。
RELOAD_HOOK="docker compose -f $COMPOSE exec -T web nginx -s reload"

case "$COMMAND" in
  issue)
    if [ ${#DOMAINS[@]} -eq 0 ]; then
      echo "要给哪个域名签证书？用 -d <域名>" >&2
      usage
    fi
    need_certbot
    mkdir -p "$WEBROOT"
    ARGS=()
    for domain in "${DOMAINS[@]}"; do
      ARGS+=(-d "$domain")
    done
    if [ -n "$EMAIL" ]; then
      ARGS+=(--email "$EMAIL")
    fi
    # webroot 校验要 80 端口能通到 webroot 目录，先确认 compose 挂上了。
    if ! grep -qE "^[[:space:]]*-[[:space:]].*infra/acme" "$COMPOSE"; then
      echo "提醒：$COMPOSE 里还没把 infra/acme 挂进 web 服务（那几行是注释着的），HTTP-01 校验会失败" >&2
    fi
    certbot certonly --webroot -w "$WEBROOT" --deploy-hook "$RELOAD_HOOK" "${ARGS[@]}"
    echo
    echo "证书签好了。把下面几行加进 $COMPOSE 的 web 服务（域名换成真的）："
    echo "  ports:"
    echo "    - \"127.0.0.1:8443:443\""
    echo "  volumes:"
    echo "    - ./apps/web/nginx-tls.conf:/etc/nginx/conf.d/tls.conf:ro"
    for domain in "${DOMAINS[@]}"; do
      echo "    - /etc/letsencrypt/live/$domain:/etc/nginx/certs:ro"
    done
    echo "    - ./infra/acme:/var/www/certbot:ro"
    ;;
  renew)
    need_certbot
    # certbot renew 读的是各域名自己的续期配置（webroot 路径记在里面）。
    certbot renew --deploy-hook "$RELOAD_HOOK"
    ;;
  check)
    if [ ${#DOMAINS[@]} -eq 0 ]; then
      echo "要看哪个域名？用 -d <域名>" >&2
      usage
    fi
    for domain in "${DOMAINS[@]}"; do
      CERT="/etc/letsencrypt/live/$domain/fullchain.pem"
      if [ ! -f "$CERT" ]; then
        echo "$domain：还没有证书（$CERT 不存在）"
        continue
      fi
      echo "$domain："
      openssl x509 -enddate -noout -in "$CERT" 2>/dev/null | sed 's/^/  /'
      openssl x509 -subject -noout -in "$CERT" 2>/dev/null | sed 's/^/  /'
    done
    ;;
  *)
    usage
    ;;
esac
