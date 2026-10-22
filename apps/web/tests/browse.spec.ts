import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
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
      global: {},
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
      global: {},
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
      global: {},
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
      global: {},
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
      global: {},
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
      global: {},
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

  /**
   * 连着改两次：第二次得基于上一次产出的新版本。
   *
   * 还按打开时的旧版本改的话，新版本里上一轮改掉的东西就被悄悄盖掉了，
   * 面板却一直报「已保存」——改动就这么没了。
   */
  it('第二次编辑基于新版本', async () => {
    const calls: { url: string; method?: string }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url: String(url), method: init?.method })
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
      global: {},
    })
    await flushPromises()

    await wrapper.find('[data-test="start-edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="file-editor"]').setValue('# 第一次\n')
    await wrapper.find('[data-test="save-file"]').trigger('click')
    await flushPromises()

    await wrapper.find('[data-test="start-edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="file-editor"]').setValue('# 第二次\n')
    await wrapper.find('[data-test="save-file"]').trigger('click')
    await flushPromises()

    const puts = calls.filter((c) => c.method === 'PUT')
    expect(puts).toHaveLength(2)
    expect(puts[0].url).toBe('/api/v1/skills/s1/versions/1.0.0/file')
    // 第二次要基于 1.0.1，不然第一轮的改动就被盖掉了
    expect(puts[1].url).toBe('/api/v1/skills/s1/versions/1.0.1/file')
  })

  /** 新建文件：产出新版本。 */
  it('新建文件产出新版本', async () => {
    const calls: { url: string; method?: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ url: String(url), method: init?.method, body: init?.body })
      if (init?.method === 'PUT') {
        return jsonResponse({
          skill_id: 's1', version: '1.0.1', file_count: 4,
          sha256: 'd'.repeat(64), status: 'published',
        })
      }
      return jsonResponse(FILES)
    }))
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: {},
    })
    await flushPromises()

    await wrapper.find('[data-test="new-file"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="new-path"]').setValue('references/new.md')
    await wrapper.find('[data-test="new-content"]').setValue('# 新文件\n')
    await wrapper.find('[data-test="create-file"]').trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')
    const body = JSON.parse(String(put?.body))
    expect(body.operation).toBe('create')
    expect(body.path).toBe('references/new.md')
    expect(wrapper.find('[data-test="save-notice"]').text()).toContain('1.0.1')
  })

  /** 删除文件：也是产出新版本。 */
  it('删除文件产出新版本', async () => {
    const calls: { method?: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ method: init?.method, body: init?.body })
      if (init?.method === 'PUT') {
        return jsonResponse({
          skill_id: 's1', version: '1.0.1', file_count: 2,
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
    // 删除要确认：window.confirm 在 jsdom 里默认返回 false，替掉它。
    vi.stubGlobal('confirm', () => true)
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: {},
    })
    await flushPromises()

    await wrapper.find('[data-test="delete-file"]').trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')
    expect(JSON.parse(String(put?.body)).operation).toBe('delete')
    expect(wrapper.find('[data-test="save-notice"]').text()).toContain('1.0.1')
  })

  /** 可执行位：默认沿用原来的，勾了才改。 */
  it('可执行位默认沿用原值', async () => {
    const calls: { method?: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ method: init?.method, body: init?.body })
      if (init?.method === 'PUT') {
        return jsonResponse({
          skill_id: 's1', version: '1.0.1', file_count: 3,
          sha256: 'd'.repeat(64), status: 'published',
        })
      }
      if (url.includes('/file?')) {
        return jsonResponse({
          version: '1.0.0', path: 'run.sh', size_bytes: 120,
          sha256: 'a'.repeat(64), binary: false, content: '#!/bin/bash\n',
        })
      }
      return jsonResponse(FILES)
    }))
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: {},
    })
    await flushPromises()

    await wrapper.find('[data-test="start-edit"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="save-file"]').trigger('click')
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')
    // 不给就是沿用原来的，服务端知道原值
    expect(JSON.parse(String(put?.body)).executable).toBeUndefined()
  })

  /**
   * 上传文件：二进制内容编码成 base64 传过去，产出新版本。
   *
   * 图片、字体这类文件塞不进文本框，只能这么传。
   */
  it('上传文件产出新版本', async () => {
    const calls: { method?: string; body?: unknown }[] = []
    vi.stubGlobal('fetch', vi.fn(async (url: string, init?: RequestInit) => {
      calls.push({ method: init?.method, body: init?.body })
      if (init?.method === 'PUT') {
        return jsonResponse({
          skill_id: 's1', version: '1.0.1', file_count: 4,
          sha256: 'd'.repeat(64), status: 'published',
        })
      }
      return jsonResponse(FILES)
    }))
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: {},
    })
    await flushPromises()

    const input = wrapper.find('[data-test="file-input"]')
    const file = new File([new Uint8Array([1, 2, 3])], 'logo.png', { type: 'image/png' })
    Object.defineProperty(input.element, 'files', { value: [file] })
    await input.trigger('change')
    await flushPromises()

    expect(wrapper.find('[data-test="upload-file-name"]').text()).toContain('logo.png')

    await wrapper.find('[data-test="upload-submit"]').trigger('click')
    // FileReader 是宏任务：读完文件才发请求，得等它。
    await vi.waitFor(() => expect(calls.some((c) => c.method === 'PUT')).toBe(true))
    await flushPromises()

    const put = calls.find((c) => c.method === 'PUT')
    const body = JSON.parse(String(put?.body))
    expect(body.operation).toBe('create')
    expect(body.path).toBe('logo.png')
    expect(body.content_base64).toBe(btoa(String.fromCharCode(1, 2, 3)))
    expect(wrapper.find('[data-test="save-notice"]').text()).toContain('1.0.1')
  })

  it('点文件读出正文', async () => {
    const fetchMock = stubFiles()
    vi.stubGlobal('fetch', fetchMock)
    const wrapper = mount(FileBrowser, {
      props: { skillId: 's1', version: '1.0.0' },
      global: {},
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
