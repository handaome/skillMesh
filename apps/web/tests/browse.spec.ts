import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import FileBrowser from '../src/components/FileBrowser.vue'

enableAutoUnmount(afterEach)
afterEach(() => vi.unstubAllGlobals())

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

const FILES = {
  version: '1.0.0',
  file_count: 3,
  files: [
    { path: 'SKILL.md', size_bytes: 120, sha256: 'a'.repeat(64) },
    { path: 'references/api.md', size_bytes: 40, sha256: 'b'.repeat(64) },
    { path: 'scripts/run.sh', size_bytes: 8, sha256: 'c'.repeat(64) },
  ],
}

/** 清单与正文一起打桩。 */
function stubFiles() {
  return vi.fn(async (url: string) => {
    if (url.includes('/file?')) {
      return jsonResponse({
        version: '1.0.0', path: 'SKILL.md', size_bytes: 120,
        sha256: 'a'.repeat(64), binary: false, content: '# 标题\n\n正文。\n',
      })
    }
    return jsonResponse(FILES)
  })
}

describe('FileBrowser', () => {
  it('列出文件并按层级缩进', async () => {
    vi.stubGlobal('fetch', stubFiles())
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const rows = wrapper.findAll('[data-test="tree-file"]').map((r) => r.text())
    expect(rows.some((t) => t.includes('SKILL.md'))).toBe(true)
    expect(rows.some((t) => t.includes('api.md'))).toBe(true)
    // 目录也列出来
    expect(wrapper.findAll('[data-test="tree-dir"]')).toHaveLength(2)
  })

  /** 同一个目录只该出现一次——按路径逐段铺平会让它重复四次。 */
  it('同一目录只列一次', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      version: '1.0.0',
      file_count: 3,
      files: [
        { path: 'SKILL.md', size_bytes: 10, sha256: 'a'.repeat(64) },
        { path: 'capacity/SKILL.md', size_bytes: 10, sha256: 'b'.repeat(64) },
        { path: 'capacity/scripts/run.sh', size_bytes: 10, sha256: 'c'.repeat(64) },
      ],
    })))
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const dirs = wrapper.findAll('[data-test="tree-dir"]').map((r) => r.text())
    expect(dirs.filter((t) => t.includes('capacity'))).toHaveLength(1)
    expect(dirs.filter((t) => t.includes('scripts'))).toHaveLength(1)
  })

  /** 点目录收起/展开——文件多的时候要能折叠。 */
  it('点目录能收起与展开', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      version: '1.0.0',
      file_count: 2,
      files: [
        { path: 'SKILL.md', size_bytes: 10, sha256: 'a'.repeat(64) },
        { path: 'references/guide.md', size_bytes: 10, sha256: 'b'.repeat(64) },
      ],
    })))
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const dir = wrapper.find('[data-test="tree-dir"]')
    expect(wrapper.findAll('[data-test="tree-file"]')).toHaveLength(2)

    await dir.trigger('click')
    await flushPromises()
    // 收起后目录下的文件不见了，目录本身还在
    expect(wrapper.findAll('[data-test="tree-file"]')).toHaveLength(1)
    expect(wrapper.findAll('[data-test="tree-dir"]')).toHaveLength(1)

    await dir.trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="tree-file"]')).toHaveLength(2)
  })

  /** 抽屉：挂载即打开，关掉要通知外面卸载。 */
  it('挂载即打开，关掉时通知外面', async () => {
    vi.stubGlobal('fetch', stubFiles())
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.find('[data-test="files-dialog"]').attributes('open')).toBeDefined()

    await wrapper.find('[data-test="files-close"]').trigger('click')
    await flushPromises()
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  /** 开抽屉就是为了看内容：清单回来后自动打开第一个文件。 */
  it('自动打开第一个文件', async () => {
    const fetchMock = stubFiles()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    expect(wrapper.find('[data-test="file-content"]').text()).toContain('正文。')
    // 读文件的请求发出去了
    expect(fetchMock.mock.calls.some(([url]) => String(url).includes('/file?'))).toBe(true)
  })

  /**
   * 编辑：改完保存 → 产出新版本。
   *
   * 版本不可变，所以保存不是 PUT 那个版本，而是 PUT 源版本并带上新内容，
   * 服务端重打包出下一个版本。
   */
  it('编辑文件保存为新版本', async () => {
    const calls: { url: string; method?: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url: String(url), method: init?.method, body: init?.body })
      if (init?.method === 'PUT') {
        return jsonResponse({
          skill_id: 's1', version: '1.0.1', file_count: 3,
          sha256: 'd'.repeat(64), status: 'published',
        })
      }
      if (url.includes('/file?')) {
        return jsonResponse({
          version: '1.0.0', path: 'SKILL.md', size_bytes: 120,
          sha256: 'a'.repeat(64), binary: false, content: '# 旧内容\n',
        })
      }
      return jsonResponse(FILES)
    }))
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    await wrapper.find('[data-test="start-edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="file-editor"]').setValue('# 新内容\n')
    await wrapper.find('[data-test="save-file"]').trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')
    expect(put?.url).toBe('/api/v1/skills/s1/versions/1.0.0/file')
    expect(JSON.parse(String(put?.body)).content).toBe('# 新内容\n')
    // 提示里要说清是哪个新版本——版本不可变，别让人以为改的是原来那个
    expect(wrapper.find('[data-test="save-notice"]').text()).toContain('1.0.1')
    expect(wrapper.emitted('edited')).toBeTruthy()
  })

  it('点文件读出正文', async () => {
    const fetchMock = stubFiles()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: { plugins: [ElementPlus] },
    })
    await flushPromises()

    const skillMd = wrapper.findAll('[data-test="tree-file"]')
      .find((r) => r.text().includes('SKILL.md'))
    await skillMd!.trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-test="file-content"]').text()).toContain('正文。')
    // 路径要带过去：接口用查询参数传路径
    const call = fetchMock.mock.calls.find(([url]) => String(url).includes('/file?'))
    expect(String(call?.[0])).toContain('path=SKILL.md')
  })
})
