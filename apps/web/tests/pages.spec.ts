import { describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import DetailPage from '../src/components/DetailPage.vue'
import SearchPage from '../src/components/SearchPage.vue'

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

describe('SearchPage', () => {
  it('渲染搜索结果并抛出打开事件', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse({ items: [SKILL], next_cursor: null, has_more: false }),
      ),
    )
    const wrapper = mount(SearchPage, { props: { token: 't' } })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('Java Debug')

    await wrapper.find('[data-test="skill-item"]').trigger('click')
    expect(wrapper.emitted('open')?.[0]).toEqual(['a'])
  })

  it('把关键字放进查询参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse({ items: [], next_cursor: null, has_more: false }),
    )
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(SearchPage, { props: { token: 't' } })
    await wrapper.find('input').setValue('rust')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(String(fetchMock.mock.calls[0][0])).toBe('/api/v1/skills?q=rust')
  })

  it('失败时展示错误码而不是静默失败', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(problemResponse(401, 'UNAUTHORIZED', '令牌无效')),
    )
    const wrapper = mount(SearchPage, { props: { token: 'bad' } })
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toContain('UNAUTHORIZED')
  })
})

describe('DetailPage', () => {
  it('展示元数据与版本列表', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockImplementation(async (url: string) => {
        if (url.includes('/versions')) {
          return jsonResponse({ items: [VERSION], next_cursor: null, has_more: false })
        }
        return jsonResponse(SKILL)
      }),
    )
    const wrapper = mount(DetailPage, { props: { skillId: 'a', token: 't' } })
    await flushPromises()

    expect(wrapper.text()).toContain('java-debug')
    expect(wrapper.text()).toContain('1.2.0')
    expect(wrapper.text()).toContain('aaaaaaaaaaaa')
    expect(wrapper.text()).toContain('abc123de')
    expect(wrapper.find('[data-test="version-row"]').exists()).toBe(true)
  })

  it('加载失败时展示错误码并可返回', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(problemResponse(404, 'NOT_FOUND', 'Skill 不存在或不可见')),
    )
    const wrapper = mount(DetailPage, { props: { skillId: 'x', token: 't' } })
    await flushPromises()

    expect(wrapper.find('[data-test="error"]').text()).toContain('NOT_FOUND')
    await wrapper.find('[data-test="back"]').trigger('click')
    expect(wrapper.emitted('back')).toBeTruthy()
  })
})
