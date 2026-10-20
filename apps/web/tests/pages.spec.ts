import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import MarketPage from '../src/pages/MarketPage.vue'

const SKILL = {
  id: 'a',
  slug: 'java-debug',
  name: 'Java Debug',
  description: '调试 Java 服务',
  visibility: 'private',
  status: 'active',
  tags: ['java'],
  latest_version: '1.2.0',
  created_at: '',
  updated_at: '',
}

const VERSION = {
  id: 'v1',
  skill_id: 'a',
  version: '1.2.0',
  channel: 'stable',
  status: 'published',
  source_commit: 'abc123def456',
  artifact: {
    id: 'art1',
    sha256: 'a'.repeat(64),
    size_bytes: 2048,
    content_type: 'application/zip',
  },
  published_at: null,
  created_at: '',
}

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

function problemResponse(status: number, code: string, detail: string) {
  return { ok: false, status, statusText: 'Error', json: async () => ({ code, detail }) }
}

/** 技能详情与版本表都打桩。 */
function stubSkills() {
  return vi.fn(async (url: string) => {
    if (url.includes('/versions')) {
      return jsonResponse({ items: [VERSION], next_cursor: null, has_more: false })
    }
    if (url.includes('/skills/')) return jsonResponse(SKILL)
    return jsonResponse({ items: [SKILL], next_cursor: null, has_more: false })
  })
}

async function mountMarket(path = '/') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: MarketPage }],
  })
  await router.push(path)
  await router.isReady()
  return mount(MarketPage, { global: { plugins: [router, ElementPlus] } })
}

describe('MarketPage', () => {
  it('渲染搜索结果', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      jsonResponse({ items: [SKILL], next_cursor: null, has_more: false }),
    ))
    const wrapper = await mountMarket()
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('Java Debug')
  })

  it('把关键字放进查询参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ items: [], next_cursor: null, has_more: false }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountMarket()
    await wrapper.find('input').setValue('rust')
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/v1/skills?q=rust')
  })

  it('失败时展示错误码而不是静默失败', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problemResponse(401, 'UNAUTHORIZED', '令牌无效')))
    const wrapper = await mountMarket()
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toContain('UNAUTHORIZED')
  })

  it('点开结果后展示详情与版本表', async () => {
    vi.stubGlobal('fetch', stubSkills())
    const wrapper = await mountMarket()
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="skill-item"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('1.2.0')
    expect(wrapper.text()).toContain('abc123def456')
    expect(wrapper.text()).toContain('aaaaaaaaaaaa')
    // 详情页顶掉搜索结果，URL 上带上 skill
    expect(wrapper.find('[data-test="skill-item"]').exists()).toBe(false)
    expect(wrapper.vm.$router.currentRoute.value.query.skill).toBe('a')
  })

  it('加载失败时展示错误码并能返回', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(problemResponse(404, 'NOT_FOUND', 'Skill 不存在或不可见')))
    const wrapper = await mountMarket('/?skill=x')
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toContain('NOT_FOUND')
    await wrapper.find('[data-test="back"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="back"]').exists()).toBe(false)
    expect(wrapper.vm.$router.currentRoute.value.query.skill).toBeUndefined()
  })

  it('地址带 ?skill= 时直接进详情', async () => {
    vi.stubGlobal('fetch', stubSkills())
    const wrapper = await mountMarket('/?skill=a')
    await flushPromises()

    expect(wrapper.find('[data-test="back"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('java-debug')
  })
})
