import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import TeamPage from '../src/pages/TeamPage.vue'
import ReviewDetailPage from '../src/pages/ReviewDetailPage.vue'
import { currentOrg, selectOrganization } from '../src/auth/currentOrg'
import { loadMe, meError, session } from '../src/auth/session'

// 组件监听当前组织，测试之间不卸载会让上一次的 watcher 继续发请求。
enableAutoUnmount(afterEach)
afterEach(() => {
  vi.unstubAllGlobals()
  session.organizations = []
  selectOrganization('')
  meError.value = ''
})

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

function problemResponse(status: number, code: string, detail: string) {
  return { ok: false, status, statusText: 'Error', json: async () => ({ code, detail }) }
}

/** 团队页用的假数据。 */
const MEMBER = {
  user_id: 'u1', display_name: 'Dev Two', email: 'd2@example.com',
  role: 'member', joined_at: '2026-10-20T00:00:00Z',
}
const TEAM = {
  id: 't1', organization_id: 'org1', slug: 'platform', name: 'Platform', status: 'active',
}
const INVITATION = {
  id: 'i1', organization_name: 'Acme', team_name: 'Platform',
  org_role: 'publisher', team_role: 'member', status: 'pending',
  expires_at: '2026-10-27T00:00:00Z', created_at: '2026-10-20T00:00:00Z',
}

async function mountTeam() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/team', component: TeamPage }],
  })
  await router.push('/team')
  await router.isReady()
  const wrapper = mount(TeamPage, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return wrapper
}

async function mountDetail(item: Record<string, unknown>) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/reviews/:skillId/:version', component: ReviewDetailPage }],
  })
  await router.push('/reviews/s1/1.0.0')
  await router.isReady()
  const wrapper = mount(ReviewDetailPage, { global: { plugins: [router, ElementPlus] } })
  await flushPromises()
  return wrapper
}

describe('B1 换组织时旧组织的慢响应', () => {
  it('旧组织的慢响应落地时不覆盖新组织的数据', async () => {
    session.organizations = [
      { id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' },
      { id: 'org2', slug: 'other', name: 'Other', role: 'owner' },
    ]
    // org1 的 teams 请求挂住不返回，模拟「A 的接口慢」
    let releaseOrg1: (v: unknown) => void = () => {}
    const org1Teams = new Promise((resolve) => { releaseOrg1 = resolve })

    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/organizations/org1/teams')) return org1Teams
      if (url.includes('/organizations/org2/teams')) {
        return jsonResponse([{ ...TEAM, id: 't2', organization_id: 'org2', name: 'OtherTeam' }])
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      if (url.includes('/invitations')) return jsonResponse([INVITATION])
      return jsonResponse([])
    })
    vi.stubGlobal('fetch', fetchMock)

    const wrapper = await mountTeam()
    // 切到 org2：org2 的三个请求先回来
    selectOrganization('org2')
    await flushPromises()
    expect(currentOrg.value?.id).toBe('org2')
    const otherTeam = wrapper.findAllComponents({ name: 'ElOption' })
      .map((o) => o.props('label'))
    expect(otherTeam).toContain('OtherTeam')

    // org1 的慢响应这时才落地——不能把 org2 的数据盖掉
    releaseOrg1(jsonResponse([TEAM]))
    await flushPromises()

    const labels = wrapper.findAllComponents({ name: 'ElOption' }).map((o) => o.props('label'))
    expect(labels).toContain('OtherTeam')
    expect(labels).not.toContain('Platform')
  })
})

describe('B3 读取失败时不再显示空态', () => {
  it('读成员失败时只显示错误，不显示「还没有成员」', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/members')) {
        return problemResponse(403, 'FORBIDDEN', '组织权限不足')
      }
      if (url.includes('/invitations')) return jsonResponse([INVITATION])
      return jsonResponse([TEAM])
    }))

    const wrapper = await mountTeam()
    expect(wrapper.find('[data-test="error"]').text()).toContain('FORBIDDEN')
    // 错误与「没有成员」同时出现，用户读到的是「这个组织没有成员」这个错误结论
    expect(wrapper.text()).not.toContain('还没有成员')
    expect(wrapper.text()).not.toContain('No Data')
  })

  it('读邀请失败时只显示错误，不显示「还没有发出过邀请」', async () => {
    session.organizations = [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }]
    vi.stubGlobal('fetch', vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/invitations')) {
        return problemResponse(404, 'NOT_FOUND', '组织不存在')
      }
      if (url.includes('/members')) return jsonResponse([MEMBER])
      return jsonResponse([TEAM])
    }))

    const wrapper = await mountTeam()
    expect(wrapper.find('[data-test="invite-error"]').text()).toContain('NOT_FOUND')
    expect(wrapper.text()).not.toContain('还没有发出过邀请')
  })
})

describe('B4 审核详情的按钮', () => {
  const REVIEW = {
    skill_id: 's1', skill_slug: 'runbook', skill_name: 'Runbook',
    version: '1.0.0', channel: 'stable', submitted_by: 'Dev Two',
    submitted_at: '2026-10-20T00:00:00Z',
    scan: { status: 'passed', summary: '未发现问题' },
    source: { provider: 'git', repository: null, ref: null, commit: null },
  }

  function detailBody(status: string) {
    return {
      item: { ...REVIEW, status },
      skill_md: '# Runbook\n\n内容',
      changelog: '',
      findings: [],
      diff: { base_version: '', truncated: false, files: [] },
    }
  }

  it('版本已发布时不显示通过/拒绝按钮', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(detailBody('published'))))
    const wrapper = await mountDetail(REVIEW)
    // 直接用 URL 打开已发布版本，页面不该给出两个必然 409 的按钮
    expect(wrapper.find('[data-test="approve"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="reject"]').exists()).toBe(false)
    expect(wrapper.find('[data-test="version-status"]').text()).toContain('published')
  })

  it('待审核的版本仍然有通过/拒绝按钮', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(detailBody('pending_review'))))
    const wrapper = await mountDetail(REVIEW)
    expect(wrapper.find('[data-test="approve"]').exists()).toBe(true)
    expect(wrapper.find('[data-test="reject"]').exists()).toBe(true)
  })
})

describe('B2 两次 /me 刷新', () => {
  it('后发的那次落地后，先发的那次不回滚组织列表', async () => {
    // 两次 /me：先发的慢，后发的快。后发的是「接受邀请」之后拉的，
    // 列表里有新组织；先发的那份是接受之前的快照。
    const responses: (() => void)[] = []
    const bodies = [
      { user: { id: 'u1', display_name: 'Dev', email: null, status: 'active' }, organizations: [] },
      {
        user: { id: 'u1', display_name: 'Dev', email: null, status: 'active' },
        organizations: [{ id: 'org9', slug: 'other', name: 'Other', role: 'member' }],
      },
    ]
    let call = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      const index = call++
      const body = bodies[index]
      await new Promise<void>((resolve) => responses.push(resolve))
      return jsonResponse(body)
    }))

    const first = loadMe()
    const second = loadMe()
    // 第二份先回
    responses[1]()
    await second
    expect(session.organizations.map((o) => o.id)).toEqual(['org9'])

    // 第一份（旧的快照）后回：不能把组织列表回滚掉
    responses[0]()
    await first
    expect(session.organizations.map((o) => o.id)).toEqual(['org9'])
  })

  it('后发的那次失败先到，旧的成功后到时错误横幅还在', async () => {
    // 后发的是「接受邀请」之后那次，它先回——但失败了（500）；
    // 先发的那次（接受之前的快照）后回。
    const responses: (() => void)[] = []
    let call = 0
    vi.stubGlobal('fetch', vi.fn(async () => {
      const index = call++
      if (index === 1) return problemResponse(500, 'INTERNAL', '炸了')
      await new Promise<void>((resolve) => responses.push(resolve))
      return jsonResponse({
        user: { id: 'u1', display_name: 'Dev', email: null, status: 'active' },
        organizations: [],
      })
    }))

    const older = loadMe()
    const newer = loadMe()
    await expect(newer).rejects.toThrow('500')
    expect(meError.value).not.toBe('')

    // 旧的成功响应这时才落地：它不能把较新那次失败留下的横幅清掉
    responses[0]()
    await older
    expect(meError.value).not.toBe('')
  })
})

describe('B5 /me 拉取失败', () => {
  it('失败被记下来，顶栏据此提示', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problemResponse(500, 'INTERNAL', '炸了')))
    await expect(loadMe()).rejects.toThrow('500')
    expect(meError.value).not.toBe('')
  })

  it('拉回来了就把错误清掉', async () => {
    vi.stubGlobal('fetch', vi.fn()
      .mockResolvedValueOnce(problemResponse(500, 'INTERNAL', '炸了'))
      .mockResolvedValue(jsonResponse({
        user: { id: 'u1', display_name: 'Dev', email: null, status: 'active' },
        organizations: [{ id: 'org1', slug: 'acme', name: 'Acme', role: 'owner' }],
      })))
    await expect(loadMe()).rejects.toThrow('500')
    expect(meError.value).not.toBe('')
    await loadMe()
    expect(meError.value).toBe('')
  })
})
