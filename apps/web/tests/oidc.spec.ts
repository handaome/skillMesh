import { afterEach, describe, expect, it, vi } from 'vitest'

// 配置是模块级缓存的：一个用例取过之后后面的用例就拿不到请求了。
// 每个用例都重置模块，各拿一份干净的。
afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('运行时 OIDC 配置', () => {
  it('issuer 与 client_id 从服务端取，授权 URL 用的是它', async () => {
    const fetchMock = vi.fn(async (_url: string) => ({
      ok: true, status: 200, statusText: 'OK',
      json: async () => ({ issuer: 'https://idp.example/realms/x', client_id: 'skillmesh-web' }),
    }))
    vi.stubGlobal('fetch', fetchMock)
    const { buildAuthorizeUrl, loadPublicConfig } = await import('../src/auth/pkce')

    const config = await loadPublicConfig()
    expect(config.issuer).toBe('https://idp.example/realms/x')
    expect(String(fetchMock.mock.calls[0][0])).toContain('/api/v1/config/public')

    const url = buildAuthorizeUrl('https://app.example/login', 'st', 'ch')
    expect(url).toContain('https://idp.example/realms/x/protocol/openid-connect/auth')
    expect(url).toContain('client_id=skillmesh-web')
    // 不能再有写死在本机 Keycloak 的那个 issuer
    expect(url).not.toContain('127.0.0.1:8180')
  })

  /** 取不到就说取不到——不能悄悄回落成写死的默认值。 */
  it('取不到就报错，不回落写死的默认值', async () => {
    const fetchMock = vi.fn(async () => ({
      ok: false, status: 404, statusText: 'Not Found',
    }))
    vi.stubGlobal('fetch', fetchMock)
    const { loadPublicConfig } = await import('../src/auth/pkce')

    await expect(loadPublicConfig()).rejects.toThrow(/404/)
  })
})
