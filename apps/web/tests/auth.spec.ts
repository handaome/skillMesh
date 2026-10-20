import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import { codeChallenge, randomVerifier } from '../src/auth/pkce'
import { session, logout } from '../src/auth/session'
import router from '../src/router'
import LoginPage from '../src/pages/LoginPage.vue'

const VERIFIER_KEY = 'skillmesh_pkce_verifier'
const STATE_KEY = 'skillmesh_pkce_state'
const REDIRECT_KEY = 'skillmesh_login_redirect'

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
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
    const wrapper = mount(LoginPage, { global: { plugins: [router, ElementPlus] } })
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
    const wrapper = mount(LoginPage, { global: { plugins: [router, ElementPlus] } })
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
    mount(LoginPage, { global: { plugins: [router, ElementPlus] } })
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
