import { reactive, ref } from 'vue'
import { exchangeCode, loadPublicConfig, refreshToken } from './pkce'
import {
  loginWithPassword as apiLogin,
  refreshLocalToken,
  registerAccount as apiRegister,
} from './local'
import type { RegisterPayload } from './local'
import type { PublicConfig } from '../types'
import { bearerHeader } from '../utils/auth'

const STORAGE_KEY = 'skillmesh_session'
/** 提前 30 秒续期，别让请求卡在过期上。 */
const REFRESH_MARGIN_MS = 30_000

/**
 * 会话是哪一类的。
 *
 * local 是内置账号（用户名 + 密码），续期走服务端自己的接口；
 * oidc 是外部 IdP，续期走 IdP 的 token 端点。混了会拿错令牌去续。
 */
export type SessionKind = 'local' | 'oidc'

export interface Me {
  id: string
  display_name: string
  email: string | null
  status: string
  /** OIDC 的 sub——邀请要绑到某个人时填的就是它。 */
  subject: string
}

export interface OrgMembership {
  id: string
  slug: string
  name: string
  role: string
}

export interface Session {
  kind: SessionKind
  accessToken: string
  refreshToken: string
  expiresAt: number
  /** id_token：登出时给外部 IdP 当 id_token_hint，让它直接结束 SSO 会话。 */
  idToken: string
  user: Me | null
  organizations: OrgMembership[]
}

function empty(): Session {
  return { kind: 'oidc', accessToken: '', refreshToken: '', expiresAt: 0, idToken: '', user: null, organizations: [] }
}

function load(): Session {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return empty()
    const parsed = JSON.parse(raw) as Partial<Session>
    const organizations = parsed.organizations ?? []
    // 形状不对就当没登录：畸形的 accessToken 会变成 `Bearer [object Object]`。
    if (typeof parsed.accessToken !== 'string' || !Array.isArray(organizations)) return empty()
    return { ...empty(), ...parsed, kind: parsed.kind ?? 'oidc', organizations }
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
    kind: session.kind,
    accessToken: session.accessToken,
    refreshToken: session.refreshToken,
    expiresAt: session.expiresAt,
    // id_token 也要存：登出时拿它当 id_token_hint，
    // 否则 IdP 只显示「要登出吗」的确认页，SSO 会话其实没结束。
    idToken: session.idToken,
    user: session.user,
    organizations: session.organizations,
  }))
}

export function setTokens(tokens: {
  access_token: string
  refresh_token?: string
  expires_in: number
  id_token?: string
}, kind: SessionKind = 'oidc') {
  session.kind = kind
  session.accessToken = tokens.access_token
  // RFC 6749 §6：refresh 响应可以不带 refresh_token，这时保留旧的。
  session.refreshToken = tokens.refresh_token ?? session.refreshToken
  // id_token 同理：refresh 响应不一定带，带了就更新，不带就留着旧的。
  if (tokens.id_token) session.idToken = tokens.id_token
  session.expiresAt = Date.now() + tokens.expires_in * 1000
  persist()
}

/**
 * /me 的代次。App 挂载与接受邀请各会拉一次，两次互不知情——
 * 后发的那份才是更新的快照，先发的那份晚到也不能把组织列表回滚掉。
 */
let meSeq = 0
/** 已生效的最高代次。比它旧的响应一律丢弃——失败也算生效（它挂出了错误横幅）。 */
let meApplied = 0

export function logout() {
  Object.assign(session, empty())
  meError.value = ''
  // 登出要让在途的 /me 作废：把代次推到已发出的最高代，那些还没回来的
  // 响应落地时就比不过 meApplied，不会把旧用户与旧组织写回已清空的会话
  // （看着像还登录着，其实每个 API 都 401）。
  meApplied = meSeq
  localStorage.removeItem(STORAGE_KEY)
}

/**
 * 主动登出：清本地会话，并结束外部 IdP 的 SSO 会话。
 *
 * 只清 localStorage 不够——浏览器里 IdP 的 SSO cookie 还在，
 * 再点「登录」会被 IdP 直接放行（不输密码），登出等于没登出：
 * 共享机器上别人接手键盘，就成了你，权限也跟着过去。
 *
 * 会话失效（401、续期失败）走的是 logout()，不跳这里：
 * 那时正要送回登录页，再多一次跳转只会让人以为登录坏了。
 *
 * issuer 与 client_id 来自服务端的公开配置：换 IdP 不用改前端代码。
 */
export async function logoutEndingSession() {
  const idToken = session.idToken
  const kind = session.kind
  const redirect = `${window.location.origin}/login`
  logout()
  // 内置账号没有 SSO 会话要结束，但还是要回登录页：
  // 停在原页面上，后续请求全是 401，用户只看到一屏报错。
  if (kind === 'local') {
    window.location.assign(redirect)
    return
  }
  let config: PublicConfig
  try {
    config = await loadPublicConfig()
  } catch {
    // 取不到 IdP 的配置就别乱跳：本地会话已经清了，回登录页。
    window.location.assign(redirect)
    return
  }
  if (!config.issuer) {
    // 服务端没接外部 IdP：没有 SSO 会话要结束。
    window.location.assign(redirect)
    return
  }
  const url = `${config.issuer}/protocol/openid-connect/logout`
    + `?client_id=${encodeURIComponent(config.client_id)}`
    + `&post_logout_redirect_uri=${encodeURIComponent(redirect)}`
    // 带 id_token_hint：IdP 才知道要结束谁的 SSO 会话。
    // 不带就只显示一个「要登出吗」的确认页——SSO 会话其实没结束，
    // 共享机器上等于给下一个人留了免密登录。
    + (idToken ? `&id_token_hint=${encodeURIComponent(idToken)}` : '')
  window.location.assign(url)
}

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
    headers: bearerHeader(session.accessToken),
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
    if (session.kind === 'local') {
      // 内置账号的续期走服务端自己的接口，IdP 那套不认识这个刷新令牌。
      setTokens(await refreshLocalToken(session.refreshToken), 'local')
    } else {
      setTokens(await refreshToken(session.refreshToken))
    }
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

/** 内置账号登录：用户名 + 密码。 */
export async function loginWithPassword(username: string, password: string) {
  setTokens(await apiLogin(username, password), 'local')
  await loadMe()
}

/** 注册并直接登录。 */
export async function registerAccount(payload: RegisterPayload) {
  setTokens(await apiRegister(payload), 'local')
  await loadMe()
}

/** 契约里的名字。 */
export const login = completeLogin
