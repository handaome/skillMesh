import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import InstallationsPage from '../src/pages/InstallationsPage.vue'
import type { Installation, Skill } from '../src/types'

const api = vi.hoisted(() => ({
  listInstallations: vi.fn(),
  searchSkills: vi.fn(),
  registerInstallation: vi.fn(),
  registerDevice: vi.fn(),
  updateInstallation: vi.fn(),
  unregisterInstallation: vi.fn(),
  revokeDevice: vi.fn(),
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
  id: '33333333-3333-3333-3333-333333333333',
  slug: 'demo',
  name: '演示 Skill',
  description: '用来测的',
  visibility: 'private',
  status: 'active',
  tags: [],
  latest_version: '1.2.0',
  created_at: '2026-10-01T00:00:00Z',
  updated_at: '2026-10-01T00:00:00Z',
}

const installation: Installation = {
  id: '11111111-1111-1111-1111-111111111111',
  skill_id: skill.id,
  device_id: '22222222-2222-2222-2222-222222222222',
  agent: 'claude-code',
  install_scope: 'user',
  target_path: '/home/me/.claude/skills/demo',
  installed_version: '1.2.0',
  installed_sha256: '',
  update_policy: 'notify',
  local_modified: false,
  status: 'active',
  installed_at: '2026-10-01T00:00:00Z',
  last_updated_at: '2026-10-01T00:00:00Z',
  last_checked_at: '2026-10-20T09:00:00Z',
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listInstallations.mockResolvedValue([installation])
  api.searchSkills.mockResolvedValue({
    items: [skill],
    next_cursor: null,
    has_more: false,
  })
  vi.stubGlobal('confirm', vi.fn(() => true))
})

describe('安装登记与设备：注销安装实例', () => {
  it('注销要二次确认并说清后果，确认后才真删', async () => {
    const wrapper = mount(InstallationsPage)
    await flushPromises()
    expect(wrapper.findAll('[data-test="installation-row"]')).toHaveLength(1)

    await wrapper.find('[data-test="unregister"]').trigger('click')
    await flushPromises()

    expect(confirm).toHaveBeenCalled()
    const message = vi.mocked(confirm).mock.calls[0][0] as string
    expect(message).toContain('注销不可逆')
    expect(message).toContain('本机的文件不会被删')
    expect(api.unregisterInstallation).toHaveBeenCalledWith(installation.id)
    expect(wrapper.findAll('[data-test="installation-row"]')).toHaveLength(0)
  })

  it('取消确认就不注销', async () => {
    vi.stubGlobal('confirm', vi.fn(() => false))
    const wrapper = mount(InstallationsPage)
    await flushPromises()

    await wrapper.find('[data-test="unregister"]').trigger('click')
    await flushPromises()

    expect(api.unregisterInstallation).not.toHaveBeenCalled()
    expect(wrapper.findAll('[data-test="installation-row"]')).toHaveLength(1)
  })
})
