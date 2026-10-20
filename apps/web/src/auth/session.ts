import { reactive, ref } from 'vue'
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
    const parsed = JSON.parse(raw) as Partial<Session>
    const organizations = parsed.organizations ?? []
    // 形状不对就当没登录：畸形的 accessToken 会变成 `Bearer [object Object]`。
    if (typeof parsed.accessToken !== 'string' || !Array.isArray(organizations)) return empty()
    return { ...empty(), ...parsed, organizations }
  } catch {
    return empty()
  }
}

export const session = reactive<Session>(load())

/**
 * 最近一次拉 /me 的失败原因。空着就是没失败。
 *
 * 放共享单例里而不是某个页面的局部状态：拉不到的不只是顶栏的用户名，
 * 组织列表也是空的——团队页会因此对真正的组织成员说「你还没有加入任何组织」。
 */
export const meError = ref('')

function persist() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    expiresAt: session.expiresAt,
    user: session.user,
    organizations: session.organizations,
  }))
}

export function setTokens(tokens: { access_token: string; refresh_token?: string; expires_in: number }) {
  session.accessToken = tokens.access_token
  // RFC 6749 §6：refresh 响应可以不带 refresh_token，这时保留旧的。
  session.refreshToken = tokens.refresh_token ?? session.refreshToken
  session.expiresAt = Date.now() + tokens.expires_in * 1000
  persist()
}

export function logout() {
  Object.assign(session, empty())
  meError.value = ''
  localStorage.removeItem(STORAGE_KEY)
}

/**
 * /me 的代次。App 挂载与接受邀请各会拉一次，两次互不知情——
 * 后发的那份才是更新的快照，先发的那份晚到也不能把组织列表回滚掉。
 */
let meSeq = 0
/** 已生效的最高代次。比它旧的响应一律丢弃——失败也算生效（它挂出了错误横幅）。 */
let meApplied = 0

/**
 * 拉一次 /me，顺便把组织列表带回来。
 *
 * 只接受比已生效结果更新的那份：受邀者点「接受邀请」后 App 挂载那次
 * 慢响应才落地的话，它带的是接受之前的组织列表——盖回去会让「去团队页」
 * 落进另一个组织。
 */
export async function loadMe(apiBase = '/api/v1') {
  const seq = ++meSeq
  const response = await fetch(`${apiBase}/me`, {
    headers: { Authorization: `Bearer ${session.accessToken}` },
  })
  if (!response.ok) {
    const message = `拉取当前用户失败: ${response.status}`
    // 已经有更新的结果生效了，这份失败是过时的事，别拿它报当前的警。
    // 否则就轮到自己生效：比它旧的响应（哪怕是成功的）不准再来清这个横幅，
    // 也不准把过期的组织列表盖回来。
    if (seq > meApplied) {
      meApplied = seq
      meError.value = message
    }
    throw new Error(message)
  }
  const body = await response.json()
  if (seq > meApplied) {
    meApplied = seq
    session.user = body.user
    session.organizations = body.organizations ?? []
    persist()
    // 只有当前生效的这代能清横幅：更旧的成功响应是「更早的快照」，
    // 它落地不代表当前状态没问题。
    meError.value = ''
  }
  return body
}

/**
 * 快过期就续期。`force` 无视过期时间直接续——401 之后要用（令牌被服务端拒了，
 * 但本地时间未必看得出来）。
 * 续不了就登出，交给调用方跳登录页。
 */
export async function ensureFresh(force = false): Promise<boolean> {
  if (!session.accessToken) return false
  if (!force && session.expiresAt - Date.now() > REFRESH_MARGIN_MS) return true
  if (!session.refreshToken) {
    // 过期了又没有 refresh token：清会话。
    // 不清的话守卫看令牌还在就放行，用户卡在一个全 401 的僵尸会话里。
    logout()
    return false
  }
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
