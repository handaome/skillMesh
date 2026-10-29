// OIDC 授权码 + PKCE。Web Crypto 原生够用，不引 oidc-client-ts。
// Keycloak client 是 public 的，没有 client_secret，只有 code_verifier 能证明是我们发的请求。

import { API_BASE } from '../constants/api'
import type { PublicConfig } from '../types'

export interface TokenResponse {
  access_token: string
  /** RFC 6749 §6：refresh 响应可以不带这个字段。 */
  refresh_token?: string
  expires_in: number
  /**
   * id_token：登出时用。
   *
   * Keycloak 的 logout 端点带上 id_token_hint 才知道「登出的是谁」，
   * 不带就只显示一个「要登出吗」的确认页——SSO 会话其实没结束，
   * 别人点「确认」就等于替他登出了。
   */
  id_token?: string
}

/**
 * 运行时拿到的 OIDC 配置。
 *
 * 原来写死在本文件里（issuer 指着 127.0.0.1 的 Keycloak），换企业 IdP 就得
 * 改代码重新打包——部署文档写的「只认 OIDC issuer，代码不用改」对网页端并不成立。
 * 现在从服务端取：取不到就是取不到，不回落写死的默认值。
 */
let config: PublicConfig | null = null
/** 正在取的那一次。并发调用共用同一个请求，别把配置端点打爆。 */
let pending: Promise<PublicConfig> | null = null

/**
 * 取一次服务端的公开配置（issuer 与 client_id），取到就缓存。
 *
 * 取不到就抛错：登录本来就起不来，硬凑一个默认 issuer 只会让人对着
 * 一个连不上的地址反复重试。
 */
export async function loadPublicConfig(): Promise<PublicConfig> {
  if (config) return config
  if (!pending) {
    pending = fetch(`${API_BASE}/config/public`)
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`取服务端公开配置失败：HTTP ${response.status}`)
        }
        const body = (await response.json()) as Partial<PublicConfig>
        // issuer 为空是合法的：服务端只开了内置账号，没接外部 IdP。
        // 这时网页端只走用户名密码，不做 OIDC 跳转。
        config = {
          issuer: body.issuer ?? '',
          client_id: body.client_id ?? '',
          local_auth: body.local_auth ?? true,
          register_open: body.register_open ?? false,
        }
        return config
      })
      .finally(() => { pending = null })
  }
  return pending
}

/** 已取到的配置。没取过就抛错——别拿默认值凑。 */
export function publicConfig(): PublicConfig {
  if (!config) throw new Error('OIDC 配置还没取到：先 await loadPublicConfig()')
  return config
}

function base64Url(bytes: Uint8Array): string {
  let binary = ''
  bytes.forEach((b) => { binary += String.fromCharCode(b) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

/** 32 字节随机 verifier。 */
export function randomVerifier(): string {
  return base64Url(crypto.getRandomValues(new Uint8Array(32)))
}

/** SHA-256 后 base64url，即 S256。 */
export async function codeChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))
  return base64Url(new Uint8Array(digest))
}

export function buildAuthorizeUrl(redirectUri: string, state: string, challenge: string): string {
  const { issuer, client_id: clientId } = publicConfig()
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  })
  return `${issuer}/protocol/openid-connect/auth?${params.toString()}`
}

async function postToken(params: Record<string, string>): Promise<TokenResponse> {
  const { issuer, client_id: clientId } = await loadPublicConfig()
  const body = new URLSearchParams({ ...params, client_id: clientId })
  const response = await fetch(`${issuer}/protocol/openid-connect/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  })
  if (!response.ok) {
    const text = await response.text()
    throw new Error(`令牌交换失败: ${response.status} ${text}`)
  }
  return (await response.json()) as TokenResponse
}

export function exchangeCode(code: string, verifier: string, redirectUri: string): Promise<TokenResponse> {
  return postToken({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    code_verifier: verifier,
  })
}

/** 静默续期。Keycloak 的 public client 默认允许 refresh。 */
export function refreshToken(refresh: string): Promise<TokenResponse> {
  return postToken({
    grant_type: 'refresh_token',
    refresh_token: refresh,
  })
}
