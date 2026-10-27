import { computed, ref } from 'vue'
import type { Ref } from 'vue'
import type { Page } from '../types'
import { errorText } from '../utils/errorText'

export interface PagedListOptions {
  /** 条数说明里的量词：Skill 说「个」，审计说「条」。 */
  unit?: string
  /**
   * 首次加载前就算「在加载」。
   *
   * 列表要等第一次响应才知道有没有数据——不预置这个状态的话，
   * 刚进页面就会先闪一下「还没有内容」。
   */
  initialLoading?: boolean
}

/**
 * 游标分页的列表。
 *
 * 服务端按游标翻页：has_more 为真才有下一页，next_cursor 是下一页的起点。
 * 只拉一页就把当前条数当总数显示，页面说的就是假话——所以这里把
 * 「还有更多」显式带出去，条数说明也跟着改口。
 *
 * fetchPage 收两个参数：本次查询的条件与游标。条件由调用方定，
 * 翻页时沿用上一次的条件——条件变了就得重新 load，
 * 拿旧条件的游标去查新条件，页面对不上。
 *
 * 每次 load 都换一个令牌：响应回来时如果已经重新加载过，这份结果就丢掉，
 * 不然慢的那次会把新结果盖回去。
 */
export function usePagedList<T, P = void>(
  fetchPage: (params: P, cursor?: string) => Promise<Page<T>>,
  options: PagedListOptions = {},
) {
  // 断言成 Ref<T[]>：ref 会把泛型拆开解包（UnwrapRef），
  // 翻页时 items.value = [...items.value, ...page.items] 就对不上了。
  const items = ref([]) as Ref<T[]>
  /** 首页在加载。翻页不归它管——翻页时列表还得留着。 */
  const loading = ref(options.initialLoading ?? false)
  const loadingMore = ref(false)
  const error = ref('')
  const nextCursor = ref<string | null>(null)
  const hasMore = ref(false)
  let token = 0
  /** 最近一次 load 用的条件。翻页要接着同一组条件查。 */
  let currentParams: P | undefined

  /** 重新取第一页，替换掉现有的。 */
  async function load(params?: P) {
    const mine = ++token
    currentParams = params
    loading.value = true
    error.value = ''
    try {
      const page = await fetchPage(params as P, undefined)
      if (mine !== token) return
      items.value = page.items
      nextCursor.value = page.next_cursor
      hasMore.value = page.has_more
    } catch (e) {
      if (mine !== token) return
      items.value = []
      error.value = errorText(e)
    } finally {
      if (mine === token) loading.value = false
    }
  }

  /** 接着上一页往后取，追加到现有的后面。 */
  async function loadMore() {
    const cursor = nextCursor.value
    if (!cursor || loading.value || loadingMore.value) return
    const mine = token
    loadingMore.value = true
    try {
      const page = await fetchPage(currentParams as P, cursor)
      if (mine !== token) return
      items.value = [...items.value, ...page.items]
      nextCursor.value = page.next_cursor
      hasMore.value = page.has_more
    } catch (e) {
      if (mine !== token) return
      // 翻页失败不清列表：已经取到的那几页还在，用户重试就行。
      error.value = errorText(e)
    } finally {
      if (mine === token) loadingMore.value = false
    }
  }

  /**
   * 清空列表，并作废还在路上的那次请求。
   *
   * 换对象时用：旧对象的内容还挂在屏幕上会让人以为看的是新的，
   * 而慢的那次响应回来正好把旧内容填回去。
   */
  function reset() {
    token++
    items.value = []
    nextCursor.value = null
    hasMore.value = false
    error.value = ''
  }

  /** 条数说明。有下一页时不能说「共 N 条」——那不是总数。 */
  const countText = computed(() => {
    const unit = options.unit ?? '条'
    return hasMore.value
      ? `已显示 ${items.value.length} ${unit}，还有更多`
      : `${items.value.length} ${unit}`
  })

  return { items, loading, loadingMore, error, nextCursor, hasMore, countText, load, loadMore, reset }
}
