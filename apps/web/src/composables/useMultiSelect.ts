import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'

export interface MultiSelectOptions<T> {
  /** 当前列表里的全部条目。 */
  items: Ref<T[]>
  /** 过滤后的列表。筛选一变，已选里不该再出现的要摘掉。 */
  filtered: Ref<T[]>
  /** 取条目的唯一键。 */
  getKey: (item: T) => string
  /** 筛选条件的变化信号。变了就裁剪选择。 */
  filterSignal: Ref<unknown>
  /** 作用域的变化信号。变了就清空选择。 */
  scopeSignal: Ref<unknown>
}

/**
 * 多选状态机。
 *
 * 三条规则都是为了避免「选中的东西已经不在眼前」——那种情况下按批量放行，
 * 放行的对象就和用户以为的不一样：
 *
 * - 筛选一变，裁剪选择：被筛掉的不再算选中；
 * - 作用域一变，清空选择：换了组织/团队还留着上一批勾选，就是放错了对象；
 * - 条目被删（审完了、被别人处理了），自动摘掉。
 */
export function useMultiSelect<T>(options: MultiSelectOptions<T>) {
  const selected = ref(new Set<string>())

  /** 选中的条目（按列表顺序）。 */
  const selectedItems = computed(() =>
    options.items.value.filter((item) => selected.value.has(options.getKey(item))))

  /** 选中的条数。工具条上显示的是它。 */
  const selectedCount = computed(() => selected.value.size)

  function isSelected(item: T) {
    return selected.value.has(options.getKey(item))
  }

  function toggle(item: T) {
    const key = options.getKey(item)
    const next = new Set(selected.value)
    if (next.has(key)) {
      next.delete(key)
    } else {
      next.add(key)
    }
    selected.value = next
  }

  function clear() {
    selected.value = new Set()
  }

  /** 只留下还在当前列表里的那些。 */
  function prune(alive: Set<string>) {
    const next = new Set([...selected.value].filter((key) => alive.has(key)))
    if (next.size !== selected.value.size) selected.value = next
  }

  watch(options.filterSignal, () => {
    prune(new Set(options.filtered.value.map(options.getKey)))
  })

  watch(options.scopeSignal, () => clear())

  watch(options.items, (items) => {
    prune(new Set(items.map(options.getKey)))
  })

  return { selected, selectedItems, selectedCount, isSelected, toggle, clear }
}
