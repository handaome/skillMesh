// OIDC 授权码 + PKCE。Web Crypto 原生够用，不引 oidc-client-ts。
// Keycloak client 是 public 的，没有 client_secret，只有 code_verifier 能证明是我们发的请求。

export const ISSUER = 'http://127.0.0.1:8180/kc/realms/skillmesh'
export const CLIENT_ID = 'skillmesh-cli'
const TOKEN_ENDPOINT = `${ISSUER}/protocol/openid-connect/token`

export interface TokenResponse {
  access_token: string
  refresh_token: string
  expires_in: number
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
  const params = new URLSearchParams({
    client_id: CLIENT_ID,
    redirect_uri: redirectUri,
    response_type: 'code',
    scope: 'openid profile email',
    state,
    code_challenge: challenge,
    code_challenge_method: 'S256',
  })
  return `${ISSUER}/protocol/openid-connect/auth?${params.toString()}`
}

async function postToken(body: URLSearchParams): Promise<TokenResponse> {
  const response = await fetch(TOKEN_ENDPOINT, {
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
  return postToken(new URLSearchParams({
    grant_type: 'authorization_code',
    code,
    redirect_uri: redirectUri,
    client_id: CLIENT_ID,
    code_verifier: verifier,
  }))
}

/** 静默续期。Keycloak 的 public client 默认允许 refresh。 */
export function refreshToken(refresh: string): Promise<TokenResponse> {
  return postToken(new URLSearchParams({
    grant_type: 'refresh_token',
    refresh_token: refresh,
    client_id: CLIENT_ID,
  }))
}
