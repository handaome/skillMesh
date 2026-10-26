import { computed, ref } from 'vue'
import type { Skill, SkillPage } from '../types'
import { errorText } from '../utils/errorText'

/**
 * 取一页：cursor 非空就是接着上一页往后取。
 *
 * filters 是额外的筛选条件（可见性、标签……）：市场页按可见性筛，
 * 「我的 Skill」页不用。
 */
export type SkillPageFilters = Record<string, string>

export type SkillPageFetcher = (
  q: string, cursor?: string, filters?: SkillPageFilters,
) => Promise<SkillPage>

/**
 * 游标分页的 Skill 列表。
 *
 * 服务端按 (updated_at, id) 倒序翻页：has_more 为真才有下一页，next_cursor
 * 是下一页的起点。只拉一页就把当前条数当总数显示，页面说的就是假话——
 * 所以这里把「还有更多」显式带出去，条数说明也跟着改口。
 */
export function useSkillList(fetchPage: SkillPageFetcher) {
  const items = ref<Skill[]>([])
  /** 首页在加载。翻页不归它管——翻页时列表还得留着。 */
  const loading = ref(false)
  const loadingMore = ref(false)
  const error = ref('')
  const nextCursor = ref<string | null>(null)
  const hasMore = ref(false)
  /** 最近一次请求用的查询词。响应回来时词已经变了，这份结果就丢掉。 */
  let current = ''
  /** 最近一次请求用的筛选条件。同理：变了就丢掉这份结果。 */
  let currentFilters: SkillPageFilters = {}

  /** 重新取第一页，替换掉现有的。 */
  async function load(q: string, filters: SkillPageFilters = {}) {
    current = q
    currentFilters = filters
    loading.value = true
    error.value = ''
    try {
      const page = await fetchPage(q, undefined, filters)
      if (current !== q) return
      items.value = page.items
      nextCursor.value = page.next_cursor
      hasMore.value = page.has_more
    } catch (e) {
      if (current !== q) return
      items.value = []
      error.value = errorText(e)
    } finally {
      if (current === q) loading.value = false
    }
  }

  /** 接着上一页往后取，追加到现有的后面。 */
  async function loadMore() {
    const cursor = nextCursor.value
    if (!cursor || loading.value || loadingMore.value) return
    const q = current
    loadingMore.value = true
    try {
      const page = await fetchPage(q, cursor, currentFilters)
      if (current !== q) return
      items.value = [...items.value, ...page.items]
      nextCursor.value = page.next_cursor
      hasMore.value = page.has_more
    } catch (e) {
      if (current !== q) return
      // 翻页失败不清列表：已经取到的那几页还在，用户重试就行。
      error.value = errorText(e)
    } finally {
      if (current === q) loadingMore.value = false
    }
  }

  /** 条数说明。有下一页时不能说「共 N 个」——那不是总数。 */
  const countText = computed(() =>
    hasMore.value
      ? `已显示 ${items.value.length} 个，还有更多`
      : `${items.value.length} 个`)

  return { items, loading, loadingMore, error, nextCursor, hasMore, countText, load, loadMore }
}
