import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { codeChallenge, randomVerifier } from '../src/auth/pkce'
import { ensureFresh, session, logout } from '../src/auth/session'
import { searchSkills } from '../src/api'
import router from '../src/router'
import LoginPage from '../src/pages/LoginPage.vue'

const VERIFIER_KEY = 'skillmesh_pkce_verifier'
const STATE_KEY = 'skillmesh_pkce_state'
const REDIRECT_KEY = 'skillmesh_login_redirect'

// fetch 的桩会漏到后面的用例。
afterEach(() => {
  vi.unstubAllGlobals()
})

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

function unauthorized() {
  return {
    ok: false, status: 401, statusText: 'Unauthorized',
    json: async () => ({ code: 'UNAUTHORIZED', detail: '令牌无效' }),
  }
}

/** 令牌端点与 /me 一起打桩。 */
function stubOidc() {
  return vi.fn(async (url: string, _init?: RequestInit) => {
    if (url.includes('/protocol/openid-connect/token')) {
      return jsonResponse({ access_token: 'a', refresh_token: 'r', expires_in: 60 })
    }
    return jsonResponse({
      user: { id: 'u', display_name: 'Dev', email: null, status: 'active' },
      organizations: [],
    })
  })
}

describe('PKCE', () => {
  it('verifier 是 43 位的 base64url', () => {
    const verifier = randomVerifier()
    expect(verifier).toHaveLength(43)
    expect(verifier).toMatch(/^[A-Za-z0-9_-]+$/)
  })

  it('challenge 是 SHA-256 后的 base64url，32 字节', async () => {
    const challenge = await codeChallenge(randomVerifier())
    expect(challenge).toHaveLength(43)
    expect(challenge).toMatch(/^[A-Za-z0-9_-]+$/)
  })

  it('同样的 verifier 得到同样的 challenge', async () => {
    const verifier = randomVerifier()
    expect(await codeChallenge(verifier)).toBe(await codeChallenge(verifier))
  })
})

describe('LoginPage', () => {
  it('没有授权码时显示登录按钮', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginPage }],
    })
    await router.push('/login')
    await router.isReady()
    const wrapper = mount(LoginPage, { global: { plugins: [router] } })
    await flushPromises()
    expect(wrapper.find('[data-test="login"]').exists()).toBe(true)
  })

  it('state 对不上就拒绝换令牌', async () => {
    const fetchMock = stubOidc()
    vi.stubGlobal('fetch', fetchMock)
    sessionStorage.setItem(STATE_KEY, 'expected')
    sessionStorage.setItem(VERIFIER_KEY, 'verifier')

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/login', component: LoginPage }],
    })
    await router.push('/login?code=abc&state=forged')
    await router.isReady()
    const wrapper = mount(LoginPage, { global: { plugins: [router] } })
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').exists()).toBe(true)
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('换到令牌后回到原路径', async () => {
    const fetchMock = stubOidc()
    vi.stubGlobal('fetch', fetchMock)
    sessionStorage.setItem(STATE_KEY, 'st')
    sessionStorage.setItem(VERIFIER_KEY, 'verifier')
    sessionStorage.setItem(REDIRECT_KEY, '/team')

    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/login', component: LoginPage },
        { path: '/team', component: { template: '<p>team</p>' } },
      ],
    })
    await router.push('/login?code=abc&state=st')
    await router.isReady()
    mount(LoginPage, { global: { plugins: [router] } })
    await flushPromises()

    expect(fetchMock).toHaveBeenCalled()
    // 换令牌时必须带上当初存下来的 verifier
    const tokenBody = String(fetchMock.mock.calls[0][1]?.body)
    expect(tokenBody).toContain('grant_type=authorization_code')
    expect(tokenBody).toContain('code_verifier=verifier')
    expect(session.accessToken).toBe('a')
    expect(session.user?.display_name).toBe('Dev')
    expect(router.currentRoute.value.path).toBe('/team')
  })
})

describe('路由守卫', () => {
  it('公开路径放行，其余跳登录页并记住原路径', async () => {
    logout()

    await router.push('/team')
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/team')

    await router.push('/invite/abc')
    expect(router.currentRoute.value.path).toBe('/invite/abc')
  })
})

describe('会话续期', () => {
  it('临近过期又续不上时会话被清空', async () => {
    session.accessToken = 'a'
    session.refreshToken = 'r'
    session.expiresAt = Date.now() + 1000
    vi.stubGlobal('fetch', vi.fn(async () => {
      throw new Error('refresh 失败')
    }))

    expect(await ensureFresh()).toBe(false)
    // 僵尸会话会让守卫以为还登录着，用户卡在满屏 401 里
    expect(session.accessToken).toBe('')

    // 另一条续不上的路：过期了又没有 refresh token。
    // 这条只能直接调 ensureFresh 才看得出差别——走 api.ts 时它自己也会登出。
    session.accessToken = 'a'
    session.refreshToken = ''
    session.expiresAt = Date.now() - 1000

    expect(await ensureFresh()).toBe(false)
    expect(session.accessToken).toBe('')
  })

  it('401 时静默续期并重试原请求一次', async () => {
    session.accessToken = 'old'
    session.refreshToken = 'r'
    session.expiresAt = Date.now() - 1000
    // 只数 /skills 的调用：续期路径还会补拉一次 /me，那是另一件事
    let skillCalls = 0
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
      if (url.includes('/protocol/openid-connect/token')) {
        return jsonResponse({ access_token: 'new', refresh_token: 'r2', expires_in: 60 })
      }
      if (!url.includes('/skills')) return jsonResponse({ user: null, organizations: [] })
      skillCalls += 1
      if (skillCalls === 1) return unauthorized()
      return jsonResponse({ items: [], next_cursor: null, has_more: false })
    })
    vi.stubGlobal('fetch', fetchMock)

    const page = await searchSkills('x')
    expect(page.items).toEqual([])
    // 一次 401 + 一次重试，不会更多
    expect(skillCalls).toBe(2)
    const calls = fetchMock.mock.calls.filter(([url]) => String(url).includes('/skills'))
    const headers = calls[1][1]?.headers as Record<string, string>
    expect(headers.Authorization).toBe('Bearer new')
  })

  it('会话过期又没有 refresh token 时清会话并跳登录页', async () => {
    session.accessToken = 'old'
    session.refreshToken = ''
    session.expiresAt = Date.now() - 1000
    session.organizations = []
    vi.stubGlobal('fetch', vi.fn(async () => unauthorized()))

    await router.push('/team')
    await expect(searchSkills('x')).rejects.toMatchObject({ code: 'UNAUTHORIZED', status: 401 })
    expect(session.accessToken).toBe('')
    // 跳登录页要带上原来想去哪
    expect(router.currentRoute.value.path).toBe('/login')
    expect(router.currentRoute.value.query.redirect).toBe('/team')
  })

  it('续期成功但重试仍是 401 时同样清会话并跳登录页', async () => {
    session.accessToken = 'old'
    session.refreshToken = 'r'
    session.expiresAt = Date.now() - 1000
    session.organizations = []
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('/protocol/openid-connect/token')) {
        return jsonResponse({ access_token: 'new', refresh_token: 'r2', expires_in: 60 })
      }
      if (!url.includes('/skills')) return jsonResponse({ user: null, organizations: [] })
      // 令牌被服务端拒了（签发方/受众不符、被吊销、时钟偏差）：续期救不了它
      return unauthorized()
    }))

    await router.push('/team')
    await expect(searchSkills('x')).rejects.toMatchObject({ code: 'UNAUTHORIZED', status: 401 })
    // 不能只抛个 401 就完事：用户得被送回登录页，否则满屏 UNAUTHORIZED，
    // 只能自己点顶栏的登出。
    expect(session.accessToken).toBe('')
    expect(router.currentRoute.value.path).toBe('/login')
  })
})
