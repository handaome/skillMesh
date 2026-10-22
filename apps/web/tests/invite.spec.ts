import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import InvitePage from '../src/pages/InvitePage.vue'
import { currentOrg } from '../src/auth/currentOrg'
import { session } from '../src/auth/session'

// 打桩的 fetch 必须还原，否则漏到后面的用例。
afterEach(() => vi.unstubAllGlobals())

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

const INVITATION = {
  id: 'i1', organization_name: 'Acme', team_name: 'Platform',
  org_role: 'member', team_role: 'member', status: 'pending',
  expires_at: '2026-10-27T00:00:00Z', created_at: '2026-10-20T00:00:00Z',
}

async function mountInvite() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/invite/:token', component: InvitePage },
      { path: '/team', component: { render: () => null } },
    ],
  })
  await router.push('/invite/abc')
  await router.isReady()
  const wrapper = mount(InvitePage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('InvitePage', () => {
  it('展示邀请摘要，未登录时引导先登录', async () => {
    session.accessToken = ''
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(INVITATION)))
    const wrapper = await mountInvite()

    expect(wrapper.text()).toContain('Acme')
    expect(wrapper.text()).toContain('Platform')
    expect(wrapper.find('[data-test="login-first"]').exists()).toBe(true)
  })

  it('已登录时显示接受按钮', async () => {
    session.accessToken = 'token'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(INVITATION)))
    const wrapper = await mountInvite()

    expect(wrapper.find('[data-test="accept"]').exists()).toBe(true)
  })

  it('点接受用 POST 打接受端点，成功后提示已加入', async () => {
    session.accessToken = 'token'
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/accept')) {
        return jsonResponse({
          invitation_id: 'i1', organization_id: 'org1', team_id: 't1',
          org_role: 'member', team_role: 'member', message: '已加入',
        })
      }
      if (url.endsWith('/me')) {
        return jsonResponse({
          user: { id: 'u1', display_name: 'Dev', email: null, status: 'active' },
          // 用户本来就在另一个组织里，所以列表第一个不是刚加入的那个——
          // 少了 selectOrganization 就会落到 org9 上，断言才钉得住。
          organizations: [
            { id: 'org9', slug: 'other', name: 'Other', role: 'member' },
            { id: 'org1', slug: 'acme', name: 'Acme', role: 'member' },
          ],
        })
      }
      return jsonResponse(INVITATION)
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountInvite()

    await wrapper.find('[data-test="accept"]').trigger('click')
    await flushPromises()

    // 接受必须是 POST：方法放错位置会发成 GET，接受了也不算数
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(String(post?.[0])).toBe('/api/v1/invitations/abc/accept')
    expect(wrapper.find('[data-test="done"]').exists()).toBe(true)
    // 接受后当前组织切到刚加入的那个（不刷 /me 就没有组织，不选就是列表第一个）
    expect(currentOrg.value?.id).toBe('org1')
  })
})
