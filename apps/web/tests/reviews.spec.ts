import { afterEach, describe, expect, it, vi } from 'vitest'
import { enableAutoUnmount, flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import ReviewQueuePage from '../src/pages/ReviewQueuePage.vue'
import ReviewDetailPage from '../src/pages/ReviewDetailPage.vue'

// 打桩的 fetch 必须还原，否则漏到后面的用例。
// 仓库里 pages.spec.ts / team.spec.ts 就缺这一行（Task 6 的 re-review 提过）。
afterEach(() => vi.unstubAllGlobals())
enableAutoUnmount(afterEach)

function jsonResponse(body: unknown) {
  return { ok: true, status: 200, statusText: 'OK', json: async () => body }
}

const REVIEW = {
  skill_id: 's1', skill_slug: 'runbook', skill_name: 'Runbook',
  version: '1.0.0', channel: 'stable', submitted_by: 'Dev Two',
  submitted_at: '2026-10-20T00:00:00Z',
  scan: { status: 'passed', summary: '未发现问题' },
  source: { provider: 'git', repository: 'https://git.example.com/x.git', ref: 'v1', commit: 'abc123' },
}

describe('ReviewQueuePage', () => {
  it('列出待审版本并展示扫描结论', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([REVIEW])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/reviews', component: ReviewQueuePage }],
    })
    await router.push('/reviews')
    await router.isReady()
    const wrapper = mount(ReviewQueuePage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="review-item"]').text()).toContain('Runbook')
    expect(wrapper.text()).toContain('扫描 passed')
  })

  it('没有待审时给出空态', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse([])))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/reviews', component: ReviewQueuePage }],
    })
    await router.push('/reviews')
    await router.isReady()
    const wrapper = mount(ReviewQueuePage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()
    expect(wrapper.text()).toContain('没有待审的版本')
  })
})

describe('ReviewDetailPage', () => {
  it('展示正文、扫描结论与文件差异', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse({
      item: REVIEW,
      skill_md: '# Runbook\n\n内容',
      changelog: '',
      // 命中项的键跟服务端 findings_json 一致：path / line / rule / detail
      findings: [{ path: 'a.sh', line: 3, rule: 'external_download', detail: '外部下载' }],
      diff: {
        base_version: '',
        truncated: false,
        files: [{ path: 'SKILL.md', kind: 'added', added: 0, removed: 0, truncated: false }],
      },
    })))
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/reviews/:skillId/:version', component: ReviewDetailPage }],
    })
    await router.push('/reviews/s1/1.0.0')
    await router.isReady()
    const wrapper = mount(ReviewDetailPage, { global: { plugins: [router, ElementPlus] } })
    await flushPromises()

    expect(wrapper.find('[data-test="skill-md"]').text()).toContain('Runbook')
    expect(wrapper.find('[data-test="diff-row"]').text()).toContain('SKILL.md')
    expect(wrapper.text()).toContain('external_download')
  })
})
