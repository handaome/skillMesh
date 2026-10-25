import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import MarketPage from '../src/pages/MarketPage.vue'

// 打桩的 fetch 必须还原，否则漏到后面的用例。
afterEach(() => vi.unstubAllGlobals())

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
  return mount(MarketPage, { global: { plugins: [router] } })
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

    // 页面进入时会先列一次可见的 Skill，所以搜索是后面的那次请求。
    const urls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(urls.some((u) => u.includes('q=rust'))).toBe(true)
  })

  it('版本表展示扫描结论', async () => {
    const fetchMock = vi.fn().mockImplementation(async (url: string) => {
      if (url.includes('/versions')) {
        return jsonResponse({
          items: [{
            id: 'v1', skill_id: 's1', version: '1.0.0', channel: 'stable',
            status: 'published', source_commit: 'abc',
            artifact: { id: 'a1', sha256: 'b'.repeat(64), size_bytes: 10, content_type: 'application/zip' },
            published_at: null, created_at: '2026-10-20T00:00:00Z',
            scan: {
              scanner_name: 'builtin', scanner_version: '0.1.0', status: 'passed',
              summary: '未发现问题', completed_at: null, findings: [],
            },
          }],
          next_cursor: null, has_more: false,
        })
      }
      return jsonResponse({
        items: [{
          id: 's1', slug: 'java-debug', name: 'Java Debug', description: '调试',
          visibility: 'public', status: 'active', tags: [],
          latest_version: '1.0.0',
          created_at: '2026-10-20T00:00:00Z', updated_at: '2026-10-20T00:00:00Z',
        }],
        next_cursor: null, has_more: false,
      })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountMarket()
    // 先搜出列表，才点得开 Skill
    await wrapper.find('[data-test="search"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="skill-item"]').trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('通过')
  })

  it('可见性筛选放进查询参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ items: [], next_cursor: null, has_more: false }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountMarket()
    const filter = wrapper.find('select[data-test="visibility-filter"]')
    await filter.setValue('public')
    await flushPromises()

    const urls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(urls.some((u) => u.includes('visibility=public'))).toBe(true)
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

  it('两次搜索时只认最后一次的结果', async () => {
    // 第一次搜索挂住不返回（慢接口），第二次先回
    let releaseFirst: (v: unknown) => void = () => {}
    const firstSearch = new Promise((resolve) => { releaseFirst = resolve })
    const OTHER = { ...SKILL, id: 'b', slug: 'python-lint', name: 'Python Lint' }
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.includes('q=java')) return firstSearch
      if (url.includes('q=python')) {
        return jsonResponse({ items: [OTHER], next_cursor: null, has_more: false })
      }
      return jsonResponse({ items: [], next_cursor: null, has_more: false })
    }))
    const wrapper = await mountMarket()

    // 走输入框的回车：搜索按钮在 loading 时是禁用的，只有回车能并发进来
    await wrapper.find('input').setValue('java')
    await wrapper.find('input').trigger('keyup.enter')
    await flushPromises()
    await wrapper.find('input').setValue('python')
    await wrapper.find('input').trigger('keyup.enter')
    await flushPromises()
    expect(wrapper.text()).toContain('python-lint')

    // 第一次（java）后到：不能盖掉第二次的结果，
    // 否则列表显示 java、输入框写着 python，用户打开的是错的 Skill
    releaseFirst(jsonResponse({ items: [SKILL], next_cursor: null, has_more: false }))
    await flushPromises()
    expect(wrapper.text()).toContain('python-lint')
    expect(wrapper.text()).not.toContain('java-debug')
  })

  it('连续打开两个 Skill 时只认后一个', async () => {
    const OTHER = { ...SKILL, id: 'b', slug: 'python-lint', name: 'Python Lint' }
    // 第一个详情挂住不返回
    let releaseFirst: (v: unknown) => void = () => {}
    const firstDetail = new Promise((resolve) => { releaseFirst = resolve })
    vi.stubGlobal('fetch', vi.fn(async (url: string) => {
      if (url.endsWith('/skills/a')) return firstDetail
      if (url.endsWith('/skills/b')) return jsonResponse(OTHER)
      if (url.includes('/versions')) {
        return jsonResponse({ items: [], next_cursor: null, has_more: false })
      }
      return jsonResponse({ items: [SKILL, OTHER], next_cursor: null, has_more: false })
    }))
    const wrapper = await mountMarket('/?skill=a')

    // 地址换成另一个 Skill，详情页跟着地址走
    await wrapper.vm.$router.replace({ query: { skill: 'b' } })
    await flushPromises()
    expect(wrapper.text()).toContain('python-lint')

    // 第一个（a）后到：不能盖掉第二个的详情，
    // 否则地址上是 ?skill=b、卡片头却显示 a
    releaseFirst(jsonResponse(SKILL))
    await flushPromises()
    expect(wrapper.text()).toContain('python-lint')
    expect(wrapper.text()).not.toContain('java-debug')
  })

  /**
   * 超过一页时得能接着往下取。
   *
   * 只拉一页就把条数当总数显示，页面说的就是假话——所以 next_cursor 得接上，
   * 条数说明也得说清「还有更多」。
   */
  it('有更多时能加载下一页，条数不装成总数', async () => {
    const OTHER = { ...SKILL, id: 'b', slug: 'python-lint', name: 'Python Lint' }
    const fetchMock = vi.fn(async (url: string) => {
      if (url.includes('cursor=cur1')) {
        return jsonResponse({ items: [OTHER], next_cursor: null, has_more: false })
      }
      return jsonResponse({ items: [SKILL], next_cursor: 'cur1', has_more: true })
    })
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = await mountMarket()
    await flushPromises()

    // 还有下一页就不能说「1 个」
    expect(wrapper.find('[data-test="count"]').text()).toContain('还有更多')

    await wrapper.find('[data-test="load-more"]').trigger('click')
    await flushPromises()

    // 第二页带着游标去取
    expect(String(fetchMock.mock.calls.at(-1)?.[0])).toContain('cursor=cur1')
    // 两页都摆在列表里
    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('python-lint')
    // 取完了才说总数
    expect(wrapper.find('[data-test="count"]').text()).toContain('2 个')
  })
})
