import { reactive } from 'vue'
import { exchangeCode, refreshToken } from './pkce'

const STORAGE_KEY = 'skillmesh_session'
/** 提前 30 秒续期，别让请求卡在过期上。 */
const REFRESH_MARGIN_MS = 30_000

export interface Me {
  id: string
  display_name: string
  email: string | null
  status: string
}

export interface OrgMembership {
  id: string
  slug: string
  name: string
  role: string
}

export interface Session {
  accessToken: string
  refreshToken: string
  expiresAt: number
  user: Me | null
  organizations: OrgMembership[]
}

function empty(): Session {
  return { accessToken: '', refreshToken: '', expiresAt: 0, user: null, organizations: [] }
}

function load(): Session {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return empty()
    const parsed = JSON.parse(raw) as Session
    return { ...empty(), ...parsed }
  } catch {
    return empty()
  }
}

export const session = reactive<Session>(load())

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    expiresAt: session.expiresAt,
    user: session.user,
    organizations: session.organizations,
  }))
}

export function setTokens(tokens: { access_token: string; refresh_token: string; expires_in: number }) {
  session.accessToken = tokens.access_token
  session.refreshToken = tokens.refresh_token
  session.expiresAt = Date.now() + tokens.expires_in * 1000
  persist()
}

export function logout() {
  Object.assign(session, empty())
  localStorage.removeItem(STORAGE_KEY)
}

/** 拉一次 /me，顺便把组织列表带回来。 */
export async function loadMe(apiBase = '/api/v1') {
  const response = await fetch(`${apiBase}/me`, {
    headers: { Authorization: `Bearer ${session.accessToken}` },
  })
  if (!response.ok) throw new Error(`拉取当前用户失败: ${response.status}`)
  const body = await response.json()
  session.user = body.user
  session.organizations = body.organizations ?? []
  persist()
  return body
}

/** 快过期就续期。续不了就登出，交给调用方跳登录页。 */
export async function ensureFresh(): Promise<boolean> {
  if (!session.accessToken) return false
  if (session.expiresAt - Date.now() > REFRESH_MARGIN_MS) return true
  if (!session.refreshToken) return false
  try {
    setTokens(await refreshToken(session.refreshToken))
    return true
  } catch {
    logout()
    return false
  }
}

/** 走完授权码换令牌。 */
export async function completeLogin(code: string, verifier: string, redirectUri: string) {
  setTokens(await exchangeCode(code, verifier, redirectUri))
  await loadMe()
}

/** 契约里的名字。 */
export const login = completeLogin
