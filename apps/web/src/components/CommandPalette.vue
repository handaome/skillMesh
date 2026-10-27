<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { searchSkills } from '../api'
import type { Skill } from '../types'

/**
 * 命令面板：⌘K / Ctrl+K 打开，搜 Skill 或跳页面。
 *
 * 两组结果：
 * - 页面动作（静态的）：侧栏那几个入口；
 * - Skill：复用现成的 GET /skills?q= 检索，服务端算真值。
 *
 * 跨实体检索（审计事件、审核条目、成员）没做——那要先给那几个端点补 q，
 * 属于独立的后端需求，不该混进这一条的成本里。
 */
const router = useRouter()

const open = ref(false)
const query = ref('')
const cursor = ref(0)
const skills = ref<Skill[]>([])
const input = ref<HTMLInputElement | null>(null)

/** 页面动作：命令面板的「去某页」都在这儿。 */
const ACTIONS = [
  { label: '市场', to: '/' },
  { label: '我的 Skill', to: '/mine' },
  { label: '团队', to: '/team' },
  { label: '审核', to: '/reviews' },
  { label: '审计', to: '/audit' },
]

interface Item {
  kind: '页面' | 'Skill'
  label: string
  to: string
}

const actions = computed(() =>
  query.value ? ACTIONS.filter((a) => a.label.includes(query.value)) : ACTIONS,
)

const items = computed<Item[]>(() => [
  ...actions.value.map((a) => ({ kind: '页面' as const, label: a.label, to: a.to })),
  ...skills.value.map((s) => ({
    kind: 'Skill' as const,
    label: s.name || s.slug,
    to: `/?skill=${encodeURIComponent(s.id)}`,
  })),
])

/** 检索防抖：击键一次发一次搜索，绝大多数结果落地时已经被下一次输入作废。 */
const SEARCH_DEBOUNCE_MS = 300
let timer: number | undefined

watch(query, (q) => {
  window.clearTimeout(timer)
  const keyword = q.trim()
  if (!keyword) {
    skills.value = []
    return
  }
  timer = window.setTimeout(async () => {
    try {
      const page = await searchSkills(keyword)
      skills.value = page.items.slice(0, 8)
    } catch {
      // 搜不到不是错：面板照常开着，页面动作还在。
      skills.value = []
    }
  }, SEARCH_DEBOUNCE_MS)
})

watch(open, async (isOpen) => {
  if (!isOpen) {
    query.value = ''
    skills.value = []
    cursor.value = 0
    return
  }
  await nextTick()
  input.value?.focus()
})

watch(items, () => {
  cursor.value = 0
})

function run(item: Item | undefined) {
  if (!item) return
  open.value = false
  router.push(item.to)
}

function onKeydown(event: KeyboardEvent) {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open.value = !open.value
    return
  }
  if (!open.value) return
  if (event.key === 'Escape') {
    event.preventDefault()
    open.value = false
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    cursor.value = Math.min(cursor.value + 1, items.value.length - 1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    cursor.value = Math.max(cursor.value - 1, 0)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    run(items.value[cursor.value])
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => {
  window.removeEventListener('keydown', onKeydown)
  window.clearTimeout(timer)
})
</script>

<template>
  <div v-if="open" class="sm-palette" data-test="command-palette">
    <div class="sm-palette-backdrop" @click="open = false" />
    <div class="sm-palette-panel" role="dialog" aria-label="命令面板">
      <input
        ref="input"
        v-model="query"
        class="sm-input sm-palette-input"
        type="search"
        placeholder="搜 Skill 或跳页面"
        data-test="palette-input"
      />
      <ul v-if="items.length" class="sm-palette-list">
        <li
          v-for="(item, index) in items"
          :key="`${item.kind}-${item.label}`"
          class="sm-palette-item"
          :class="{ 'sm-palette-on': index === cursor }"
          :data-test="`palette-item-${index}`"
          @mouseenter="cursor = index"
          @click="run(item)"
        >
          <span class="sm-palette-kind">{{ item.kind }}</span>
          <span class="sm-truncate">{{ item.label }}</span>
        </li>
      </ul>
      <p v-else class="sm-empty sm-sm sm-palette-empty">没有匹配的东西。</p>
    </div>
  </div>
</template>
