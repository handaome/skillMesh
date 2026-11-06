import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import SkillUsagePage from '../src/pages/SkillUsagePage.vue'
import type { SkillUsageEvent, SkillUsageStat } from '../src/types'

const api = vi.hoisted(() => ({
  listSkillUsageStats: vi.fn(),
  listSkillUsageEvents: vi.fn(),
  listSkillUsageSummary: vi.fn(),
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

const stats: SkillUsageStat[] = [
  { skill: 'verify', call_count: 6, user_count: 2, last_used_at: '2026-11-05T02:00:00Z' },
  { skill: 'graphify', call_count: 3, user_count: 1, last_used_at: '2026-11-04T10:00:00Z' },
]

const events: SkillUsageEvent[] = [
  {
    occurred_at: '2026-11-05T02:00:00Z',
    skill: 'verify',
    project: 'D:\\proj',
    args_preview: '--strict',
    agent: 'claude-code',
  },
]

beforeEach(() => {
  vi.clearAllMocks()
  api.listSkillUsageStats.mockResolvedValue(stats)
  // 汇总里的人数是全局去重的：同一个人用了两个 Skill 只算一个，
  // 不能把上面两个 user_count（2 + 1）相加。
  api.listSkillUsageSummary.mockResolvedValue({
    total_calls: 9,
    skill_count: 2,
    user_count: 2,
  })
  api.listSkillUsageEvents.mockResolvedValue({
    items: events,
    next_cursor: null,
    has_more: false,
  })
})

describe('Skill 调用统计', () => {
  it('概览数字取自汇总：总调用次数、Skill 数、人数', async () => {
    const wrapper = mount(SkillUsagePage)
    await flushPromises()

    expect(wrapper.find('[data-test="total-calls"]').text()).toBe('9')
    expect(wrapper.find('[data-test="skill-count"]').text()).toBe('2')
    // 人数取汇总里的全局去重值，不是各 Skill 相加（2 + 1 = 3）
    expect(wrapper.find('[data-test="user-count"]').text()).toBe('2')
    expect(wrapper.findAll('[data-test="stat-row"]')).toHaveLength(2)
    expect(wrapper.findAll('[data-test="usage-event"]')).toHaveLength(1)
  })

  it('换时间范围就重新统计，条件跟着传给服务端', async () => {
    const wrapper = mount(SkillUsagePage)
    await flushPromises()
    expect(api.listSkillUsageStats).toHaveBeenCalledWith(30, undefined)

    await wrapper.find('[data-test="days-filter"]').setValue('7')
    await flushPromises()

    expect(api.listSkillUsageStats).toHaveBeenCalledWith(7, undefined)
  })

  it('按 Skill 名过滤：防抖之后带着词重新统计', async () => {
    const wrapper = mount(SkillUsagePage)
    await flushPromises()

    await wrapper.find('[data-test="skill-filter"]').setValue('ver')
    await new Promise((resolve) => setTimeout(resolve, 350))
    await flushPromises()

    expect(api.listSkillUsageStats).toHaveBeenCalledWith(30, 'ver')
  })

  it('还有下一页就说清还有，点「加载更多」接着往下取', async () => {
    api.listSkillUsageEvents
      .mockResolvedValueOnce({ items: events, next_cursor: 'c1', has_more: true })
      .mockResolvedValueOnce({
        items: [
          {
            occurred_at: '2026-11-01T02:00:00Z',
            skill: 'graphify',
            project: 'D:\\other',
            args_preview: '',
            agent: 'claude-code',
          },
        ],
        next_cursor: null,
        has_more: false,
      })

    const wrapper = mount(SkillUsagePage)
    await flushPromises()

    // 有下一页时不能说「共 N 条」——那不是总数。
    expect(wrapper.text()).toContain('已显示 1 条，还有更多')

    await wrapper.find('[data-test="load-more"]').trigger('click')
    await flushPromises()

    expect(api.listSkillUsageEvents).toHaveBeenLastCalledWith('c1')
    expect(wrapper.findAll('[data-test="usage-event"]')).toHaveLength(2)
    expect(wrapper.text()).toContain('2 条')
  })
})
