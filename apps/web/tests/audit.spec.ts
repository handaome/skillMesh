import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import AuditPage from '../src/pages/AuditPage.vue'

// 打桩的 fetch 必须还原，否则漏到后面的用例。
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

const EVENTS = [
  {
    occurred_at: '2026-10-20T08:00:00Z',
    action: 'version.approve',
    target_type: 'skill',
    target_id: 's1',
    outcome: 'success',
    details: { version: '1.0.0' },
  },
  {
    occurred_at: '2026-10-20T09:00:00Z',
    action: 'access.denied',
    target_type: 'request',
    target_id: '/api/v1/skills/s2',
    outcome: 'denied',
    details: { method: 'GET', status: 404 },
  },
]

async function mountPage(body: unknown) {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(body)))
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/audit', component: AuditPage }],
  })
  await router.push('/audit')
  await router.isReady()
  const wrapper = mount(AuditPage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('AuditPage', () => {
  it('列出审计事件并给出人话读法', async () => {
    const wrapper = await mountPage(EVENTS)

    const items = wrapper.findAll('[data-test="audit-item"]')
    expect(items).toHaveLength(2)
    // 动作标识符翻成人话
    expect(items[0].text()).toContain('审核放行')
    expect(items[0].text()).toContain('成功')
    // 被拒的事件要看得出来
    expect(items[1].text()).toContain('访问被拒')
    expect(items[1].text()).toContain('被拒')
  })

  it('没有记录时给出空态', async () => {
    const wrapper = await mountPage([])
    expect(wrapper.text()).toContain('还没有审计记录')
  })

  it('取审计流水走 /audit-events', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(EVENTS))
    vi.stubGlobal('fetch', fetchMock)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/audit', component: AuditPage }],
    })
    await router.push('/audit')
    await router.isReady()
    mount(AuditPage, { global: { plugins: [router] } })
    await flushPromises()

    const url = (fetchMock.mock.calls[0][0] as string)
    expect(url).toContain('/api/v1/audit-events')
  })
})
