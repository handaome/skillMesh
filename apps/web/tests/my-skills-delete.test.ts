import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import MySkillsPage from '../src/pages/MySkillsPage.vue'
import type { Skill } from '../src/types'

const api = vi.hoisted(() => ({
  searchMySkills: vi.fn(),
  listDeletedSkills: vi.fn(),
  deleteSkill: vi.fn(),
  restoreSkill: vi.fn(),
  getVersions: vi.fn(),
  listRecycleFiles: vi.fn(),
  revokeVersion: vi.fn(),
  restoreRecycleFile: vi.fn(),
  createSkill: vi.fn(),
  updateSkill: vi.fn(),
}))

vi.mock('../src/api', () => ({
  ApiError: class ApiError extends Error {
    constructor(
      readonly code: string,
      readonly status: number,
      message: string,
    ) {
      super(message)
    }
  },
  ...api,
}))

const skill: Skill = {
  id: '11111111-1111-1111-1111-111111111111',
  slug: 'demo',
  name: '演示 Skill',
  description: '用来测的',
  visibility: 'private',
  status: 'active',
  tags: [],
  latest_version: '1.0.0',
  created_at: '2026-10-01T00:00:00Z',
  updated_at: '2026-10-01T00:00:00Z',
}

beforeEach(() => {
  vi.clearAllMocks()
  api.searchMySkills.mockResolvedValue({
    items: [skill],
    next_cursor: null,
    has_more: false,
  })
  api.listDeletedSkills.mockResolvedValue({
    items: [
      {
        id: '22222222-2222-2222-2222-222222222222',
        slug: 'gone',
        name: '删掉的 Skill',
        deleted_at: '2026-10-26T10:00:00Z',
      },
    ],
    next_cursor: null,
    has_more: false,
  })
  api.getVersions.mockResolvedValue({ items: [], next_cursor: null, has_more: false })
  api.listRecycleFiles.mockResolvedValue([])
  vi.stubGlobal('confirm', vi.fn(() => true))
})

describe('我的 Skill：删除与还原', () => {
  it('选中后详情里有删除按钮', async () => {
    const wrapper = mount(MySkillsPage)
    await flushPromises()
    expect(wrapper.findAll('[data-test="delete-skill"]')).toHaveLength(0)
    await wrapper.find('[data-test="mine-item"]').trigger('click')
    await flushPromises()
    expect(wrapper.findAll('[data-test="delete-skill"]')).toHaveLength(1)
  })

  it('删除会二次确认，成功后刷新两个列表', async () => {
    const wrapper = mount(MySkillsPage)
    await flushPromises()
    // 先打开「已删除」，删除后才需要把那个列表也刷新一遍。
    await wrapper.find('[data-test="open-deleted"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="mine-item"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="delete-skill"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    const message = vi.mocked(confirm).mock.calls[0][0] as string
    expect(message).toContain('已删除')
    expect(message).toContain('各端同步后本地那份也会被删掉')
    expect(api.deleteSkill).toHaveBeenCalledWith(skill.id)
    // 删完要把两个列表都重拉一遍。
    expect(api.searchMySkills).toHaveBeenCalledTimes(2)
    expect(api.listDeletedSkills).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-test="list-notice"]').text()).toContain('演示 Skill')
  })

  it('取消确认就不删', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    const wrapper = mount(MySkillsPage)
    await flushPromises()
    await wrapper.find('[data-test="mine-item"]').trigger('click')
    await flushPromises()
    await wrapper.find('[data-test="delete-skill"]').trigger('click')
    await flushPromises()
    expect(api.deleteSkill).not.toHaveBeenCalled()
  })

  it('打开「已删除」列出归档的 Skill，还原后刷新两个列表', async () => {
    const wrapper = mount(MySkillsPage)
    await flushPromises()
    await wrapper.find('[data-test="open-deleted"]').trigger('click')
    await flushPromises()

    expect(api.listDeletedSkills).toHaveBeenCalled()
    const rows = wrapper.findAll('[data-test="restore-skill"]')
    expect(rows).toHaveLength(1)
    expect(wrapper.text()).toContain('删掉的 Skill')
    expect(wrapper.text()).toContain('gone')

    await rows[0].trigger('click')
    await flushPromises()
    expect(api.restoreSkill).toHaveBeenCalledWith('22222222-2222-2222-2222-222222222222')
    expect(wrapper.find('[data-test="list-notice"]').text()).toContain('已还原')
  })

  it('已删除为空时给一句提示', async () => {
    api.listDeletedSkills.mockResolvedValue({ items: [], next_cursor: null, has_more: false })
    const wrapper = mount(MySkillsPage)
    await flushPromises()
    await wrapper.find('[data-test="open-deleted"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('还没有删除过 Skill')
  })
})
