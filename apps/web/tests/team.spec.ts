import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import TeamPage from '../src/pages/TeamPage.vue'
import { session } from '../src/auth/session'

// 组件监听当前组织，测试之间不卸载会让上一次的 watcher 继续发请求。
enableAutoUnmount(afterEach)
// 打桩的 fetch 必须还原，否则漏到后面的用例。
afterEach(() => vi.unstubAllGlobals())

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

function noContent() {
  return { ok: true, status: 204, statusText: 'No Content', json: async () => null }
}

/** 邀请列表现在是一页：这一页的条目 + 下一页的起点 + 还有没有更多。 */
function invitationPage(items: unknown[], next: string | null = null) {
  return jsonResponse({ items, next_cursor: next, has_more: next !== null })
}

function makeRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/team', component: TeamPage }],
  })
}

async function mountTeam() {
  const router = makeRouter()
  await router.push('/team')
  await router.isReady()
  const wrapper = mount(TeamPage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

const MEMBER = {
  user_id: 'u1', display_name: 'Dev Two', email: 'd2@example.com',
  role: 'member', joined_at: '2026-10-20T00:00:00Z',
  // 邀请要绑到某个人时填的就是这个 sub
  subject: 'sub-u1',
  status: 'active',
}

const TEAM = {
  id: 't1', organization_id: 'org1', slug: 'platform', name: 'Platform', status: 'active',
}

const INVITATION = {
  id: 'i1', organization_name: 'Acme', team_name: 'Platform',
  org_role: 'publisher', team_role: 'member', status: 'pending',
  expires_at: '2026-10-27T00:00:00Z', created_at: '2026-10-20T00:00:00Z',
}

describe('TeamPage', () => {
  it('没有组织时提示先建组织', async () => {
    session.organizations = []
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])))
    const wrapper = await mountTeam()
    expect(wrapper.text()).toContain('还没有加入任何组织')
  })

  it('列出成员与邀请', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    }))
    const wrapper = await mountTeam()

    expect(wrapper.find('[data-test="member-row"]').text()).toContain('Dev Two')
    expect(wrapper.find('[data-test="invitation-row"]').text()).toContain('Platform')
    // 列表给的是「实际授予的组织角色」，团队角色另列
    expect(wrapper.find('[data-test="invitation-org-role"]').text()).toBe('publisher')
    expect(wrapper.find('[data-test="invitation-team-role"]').text()).toBe('member')
  })

  // 被禁用的成员还挂在花名册上，只是登不进来了——列表要标出来。
  it('被禁用的成员显示已禁用标记', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/members')) {
        return jsonResponse([{ ...MEMBER, status: 'disabled' }])
      }
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    }))
    const wrapper = await mountTeam()

    expect(wrapper.find('[data-test="member-disabled"]').text()).toContain('已禁用')
  })

  // 邀请只撤销不删，越攒越多：列表要能一页一页翻，不能只给最近几条。
  it('有下一页时能加载下一页', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) {
        if (url.includes('cursor=')) {
          return invitationPage([{ ...INVITATION, id: 'i2' }])
        }
        return invitationPage([INVITATION], 'c1')
      }
      return jsonResponse([TEAM])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    // 首页只有一条，但服务端说还有更多——页面得说清，不能装作这就是全部
    expect(wrapper.findAll('[data-test="invitation-row"]')).toHaveLength(1)
    expect(wrapper.text()).toContain('还有更多')

    await wrapper.find('[data-test="load-more-invitations"]').trigger('click')
    await flushPromises()

    expect(wrapper.findAll('[data-test="invitation-row"]')).toHaveLength(2)
    const second = fetchMock.mock.calls.filter(([url]) => String(url).includes('cursor='))
    expect(second).toHaveLength(1)
    expect(String(second[0][0])).toContain('cursor=c1')
    // 翻完了就不用再说「还有更多」
    expect(wrapper.text()).not.toContain('还有更多')
  })

  it('改组织角色会 PATCH 并重读成员列表', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    let role = MEMBER.role
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        role = JSON.parse(String(init.body)).role
        return noContent()
      }
      if (url.includes('/members')) return jsonResponse([{ ...MEMBER, role }])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    const roleEdit = wrapper.find('select[data-test="role-edit"]')
    await roleEdit.setValue('publisher')
    await flushPromises()

    const patch = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH')
    expect(patch?.[0]).toBe('/api/v1/organizations/org1/members/u1')
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ role: 'publisher' })
    // PATCH 之后重读列表，界面上的角色才是服务端确认过的值
    expect((wrapper.find('select[data-test="role-edit"]').element as HTMLSelectElement).value)
      .toBe('publisher')
  })

  it('改角色失败时显示服务端错误码', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') {
        return {
          ok: false, status: 403, statusText: 'Error',
          json: async () => ({ code: 'FORBIDDEN', detail: '组织权限不足' }),
        }
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    }))
    const wrapper = await mountTeam()

    const roleEdit = wrapper.find('select[data-test="role-edit"]')
    await roleEdit.setValue('publisher')
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toContain('FORBIDDEN')
    // 改失败就还是服务端的角色，界面不能先显示一个假的
    expect((wrapper.find('select[data-test="role-edit"]').element as HTMLSelectElement).value)
      .toBe('member')
  })

  it('选中团队时改的是团队角色，打到团队路径', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'PATCH') return noContent()
      // 两层角色不同：组织里是 publisher，团队里只是 member
      if (url.includes('/teams/t1/members')) {
        return jsonResponse([{ ...MEMBER, role: 'member' }])
      }
      if (url.includes('/members')) return jsonResponse([{ ...MEMBER, role: 'publisher' }])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    const teamSelect = wrapper.find('select[data-test="team-select"]')
    await teamSelect.setValue('t1')
    await flushPromises()

    // 团队视图显示的是团队角色，不是组织角色
    expect((wrapper.find('select[data-test="role-edit"]').element as HTMLSelectElement).value)
      .toBe('member')

    const roleEdit = wrapper.find('select[data-test="role-edit"]')
    await roleEdit.setValue('reviewer')
    await flushPromises()

    // 改的是团队角色，打的必须是团队路径（打组织路径就改不到审核放行权那一层）
    const patch = fetchMock.mock.calls.find(([, init]) => init?.method === 'PATCH')
    expect(patch?.[0]).toBe('/api/v1/organizations/org1/teams/t1/members/u1')
    expect(JSON.parse(String(patch?.[1]?.body))).toEqual({ role: 'reviewer' })
  })

  it('换团队后重读该团队的成员', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/teams/t1/members')) {
        return jsonResponse([{ ...MEMBER, user_id: 'u2', display_name: 'Team Dev' }])
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    const teamSelect = wrapper.find('select[data-test="team-select"]')
    await teamSelect.setValue('t1')
    await flushPromises()

    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/teams/t1/members'))).toBe(true)
    expect(wrapper.find('[data-test="member-row"]').text()).toContain('Team Dev')
  })

  it('切团队时旧团队的行不会留在表里', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    // 新团队的成员请求挂住不返回，模拟「切了团队、响应还没回来」
    let releaseTeamMembers: (v: unknown) => void = () => {}
    const pendingTeamMembers = new Promise((resolve) => { releaseTeamMembers = resolve })
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/teams/t2/members')) return pendingTeamMembers
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM, { ...TEAM, id: 't2', slug: 'infra', name: 'Infra' }])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()
    expect(wrapper.find('[data-test="member-row"]').text()).toContain('Dev Two')

    const teamSelect = wrapper.find('select[data-test="team-select"]')
    await teamSelect.setValue('t2')
    await flushPromises()

    // 响应回来之前，旧团队的行不能还在表里：那行的下拉还能改角色，
    // 而 PATCH 打到的是新团队——改错了人。
    expect(wrapper.find('[data-test="member-row"]').exists()).toBe(false)

    releaseTeamMembers(jsonResponse([{ ...MEMBER, user_id: 'u2', display_name: 'Infra Dev' }]))
    await flushPromises()
    expect(wrapper.find('[data-test="member-row"]').text()).toContain('Infra Dev')
  })

  it('没选团队时邀请不带 team_role', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({
          id: 'i2', organization_slug: 'acme', organization_name: 'Acme',
          team_slug: null, team_name: null, org_role: 'member', team_role: 'member',
          expires_at: '2026-10-27T00:00:00Z', invite_url: 'http://127.0.0.1:5174/invite/tok', token: 'tok',
        })
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    await wrapper.find('[data-test="invite"]').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    // 给了团队角色就必须给团队，否则服务端 400
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ org_role: 'member' })
    expect(wrapper.find('[data-test="invite-link"]').text()).toContain('/invite/tok')
  })

  it('选了团队时邀请带上 team_id 与 team_role', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({
          id: 'i3', organization_slug: 'acme', organization_name: 'Acme',
          team_slug: 'platform', team_name: 'Platform',
          org_role: 'member', team_role: 'member',
          expires_at: '2026-10-27T00:00:00Z',
          invite_url: 'http://127.0.0.1:5174/invite/raw-tok', token: 'raw-tok',
        })
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([INVITATION])
      return jsonResponse([TEAM])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    const teamSelect = wrapper.find('select[data-test="team-select"]')
    await teamSelect.setValue('t1')
    await flushPromises()

    await wrapper.find('[data-test="invite"]').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({
      team_id: 't1', org_role: 'member', team_role: 'member',
    })
    // 链接指向本机，原始 token 得单独给出来（设计文档 §5.1）
    expect(wrapper.find('[data-test="invite-token"]').text()).toBe('raw-tok')
  })
  it('能建组织：填 slug 与名称后 POST /organizations，然后重拉 /me', async () => {
    session.organizations = []
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({ id: 'org2', slug: 'acme', name: 'Acme', role: 'owner' })
      }
      if (url.includes('/me')) {
        return jsonResponse({ user: { id: 'u0', display_name: 'Dev' }, organizations: [
          { id: 'org2', slug: 'acme', name: 'Acme', role: 'owner' },
        ] })
      }
      if (url.includes('/invitations')) return invitationPage([])
      return jsonResponse([])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    await wrapper.find('input[data-test="org-slug"]').setValue('acme')
    await wrapper.find('input[data-test="org-name"]').setValue('Acme')
    await wrapper.find('[data-test="create-org"]').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post?.[0]).toBe('/api/v1/organizations')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ slug: 'acme', name: 'Acme' })
    // 组织列表要跟着重拉：顶栏与团队页都从 session.organizations 算出来
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/me'))).toBe(true)
  })

  // 邀请链接是要转发出去的：不绑定的话，拿到链接的人都能以被邀请的角色进来
  // （admin 角色的链接尤其危险）。绑到成员后只有他能接受。
  it('邀请可以绑定到具体成员：请求带 subject', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') {
        return jsonResponse({ id: 'i1', token: 'raw-tok', invite_url: 'http://x/invite/raw-tok' })
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([])
      return jsonResponse([])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    const subjectSelect = wrapper.find('select[data-test="invite-subject"]')
    await subjectSelect.setValue('sub-u1')
    await flushPromises()

    await wrapper.find('[data-test="invite"]').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(JSON.parse(String(post?.[1]?.body)).subject).toBe('sub-u1')
  })

  it('能建团队：填 slug 后 POST /organizations/{id}/teams', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    const fetchMock = vi.fn().mockImplementation(async (url: string, init?: RequestInit) => {
      if (init?.method === 'POST') return jsonResponse(TEAM)
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return invitationPage([])
      return jsonResponse([])
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountTeam()

    await wrapper.find('[data-test="create-team-toggle"]').trigger('click')
    await wrapper.find('input[data-test="team-slug"]').setValue('platform')
    await wrapper.find('input[data-test="team-name"]').setValue('Platform')
    await wrapper.find('[data-test="create-team"]').trigger('click')
    await flushPromises()

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')
    expect(post?.[0]).toBe('/api/v1/organizations/org1/teams')
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ slug: 'platform', name: 'Platform' })
    // 建完要出现在团队下拉里
    const options = wrapper.findAll('select[data-test="team-select"] option').map((o) => o.text())
    expect(options).toContain('Platform')
    expect(wrapper.text()).toContain('团队 Platform 已创建')
  })
})

