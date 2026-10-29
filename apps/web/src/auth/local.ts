// 内置账号：用户名 + 密码。服务端不部署 Keycloak 也能登录。
//
// 与 OIDC 那条路并存：服务端没接外部 IdP 时（issuer 为空），
// 网页端只走这里。

import { API_BASE } from '../constants/api'

export interface LocalTokenResponse {
  access_token: string
  refresh_token: string
  expires_in: number
  username: string | null
}

export interface RegisterPayload {
  username: string
  password: string
  display_name?: string
  email?: string
}

/** 把 problem+json 的错误体变成一句话给人看。 */
async function problemText(response: Response, fallback: string): Promise<string> {
  try {
    const body = await response.json()
    if (body?.detail) return String(body.detail)
    if (body?.title) return String(body.title)
  } catch {
    // 不是 JSON：落到兜底文案
  }
  return `${fallback}（HTTP ${response.status}）`
}

async function post(path: string, payload: unknown, fallback: string): Promise<LocalTokenResponse> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  })
  if (!response.ok) throw new Error(await problemText(response, fallback))
  return (await response.json()) as LocalTokenResponse
}

/** 用户名 + 密码换令牌。 */
export function loginWithPassword(username: string, password: string): Promise<LocalTokenResponse> {
  return post('/auth/login', { username, password }, '登录失败')
}

/** 自助注册，成功即登录。 */
export function registerAccount(payload: RegisterPayload): Promise<LocalTokenResponse> {
  return post('/auth/register', payload, '注册失败')
}

/** 用刷新令牌换一对新令牌。 */
export function refreshLocalToken(refreshToken: string): Promise<LocalTokenResponse> {
  return post('/auth/refresh', { refresh_token: refreshToken }, '续期失败')
}
