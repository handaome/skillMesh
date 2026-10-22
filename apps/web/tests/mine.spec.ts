import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import MySkillsPage from '../src/pages/MySkillsPage.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

const SKILL = {
  id: 's1', slug: 'java-debug', name: 'Java Debug', description: '调试 Java 服务',
  visibility: 'private', status: 'active', tags: ['java'],
  latest_version: '1.2.0', created_at: '2026-10-20T00:00:00Z', updated_at: '2026-10-21T00:00:00Z',
}

const VERSION = {
  id: 'v1', skill_id: 's1', version: '1.2.0', channel: 'stable', status: 'published',
  source_commit: 'abc123', artifact: { id: 'a1', sha256: 'b'.repeat(64), size_bytes: 10, content_type: 'application/zip' },
  published_at: null, created_at: '2026-10-20T00:00:00Z',
}

async function mountMine() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/mine', component: MySkillsPage }],
  })
  await router.push('/mine')
  await router.isReady()
  const wrapper = mount(MySkillsPage, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('MySkillsPage', () => {
  it('列出 mine=true 的自己的 Skill', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ items: [SKILL], next_cursor: null, has_more: false }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountMine()

    expect(String(fetchMock.mock.calls[0][0])).toContain('mine=true')
    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('Java Debug')
  })

  it('改名后 PATCH 落库并更新列表', async () => {
    const calls: { url: string; method?: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url: String(url), method: init?.method, body: init?.body })
      if (init?.method === 'PATCH') {
        return jsonResponse({ ...SKILL, name: '新名字' })
      }
      if (String(url).includes('/versions')) {
        return jsonResponse({ items: [VERSION], next_cursor: null, has_more: false })
      }
      return jsonResponse({ items: [SKILL], next_cursor: null, has_more: false })
    }))
    const wrapper = await mountMine()

    await wrapper.find('[data-test="mine-item"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="edit-name"]').setValue('新名字')
    await wrapper.find('[data-test="save"]').trigger('click')
    await flushPromises()

    const patch = calls.find((c) => c.method === 'PATCH')
    expect(patch?.url).toBe('/api/v1/skills/s1')
    expect(JSON.parse(String(patch?.body)).name).toBe('新名字')
    expect(wrapper.text()).toContain('新名字')
  })

  /** 版本要能看到时间——不然不知道哪个版本是后发的。 */
  it('版本列表显示时间', async () => {
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (String(url).includes('/versions')) {
        return jsonResponse({ items: [VERSION], next_cursor: null, has_more: false })
      }
      return jsonResponse({ items: [SKILL], next_cursor: null, has_more: false })
    }))
    const wrapper = await mountMine()

    await wrapper.find('[data-test="mine-item"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="version-time"]').text()).toMatch(
      /创建于 \d{4}-\d{2}-\d{2} \d{2}:\d{2}/,
    )
  })
})
