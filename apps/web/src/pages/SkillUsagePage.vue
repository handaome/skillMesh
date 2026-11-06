<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { listSkillUsageEvents, listSkillUsageStats, listSkillUsageSummary } from '../api'
import { usePagedList } from '../composables/usePagedList'
import type { SkillUsageEvent, SkillUsageStat, SkillUsageSummary } from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'

/** 统计窗口。页面上只给三档常用的，够看清「最近还有没有人在用」。 */
const DAY_OPTIONS = [7, 30, 90] as const

/** 输入防抖。击键一次发一次统计请求，绝大多数响应落地时词已经变了。 */
const FILTER_DEBOUNCE_MS = 300

const days = ref(30)
const skillKeyword = ref('')

const stats = ref<SkillUsageStat[]>([])
const summary = ref<SkillUsageSummary | null>(null)
const statsLoading = ref(false)
const statsError = ref('')

/**
 * 还没查到数据时别显示 0：0 是「一次都没用」，不是「还没加载」。
 */
const pending = computed(() => statsLoading.value && !stats.value.length)

/**
 * 调用流水，按游标往下翻。
 *
 * 流水只增不减：只显示最近一页的话，早先的调用就再也翻不到了。
 */
const {
  items: events,
  loading: eventsLoading,
  loadingMore: eventsLoadingMore,
  error: eventsError,
  hasMore: eventsHasMore,
  countText,
  load: loadEvents,
  loadMore,
} = usePagedList<SkillUsageEvent>((_params, cursor) => listSkillUsageEvents(cursor), {
  initialLoading: true,
})

async function loadStats() {
  statsLoading.value = true
  statsError.value = ''
  try {
    const filter = skillKeyword.value.trim() || undefined
    stats.value = await listSkillUsageStats(days.value, filter)
    summary.value = await listSkillUsageSummary(days.value, filter)
  } catch (e) {
    stats.value = []
    summary.value = null
    statsError.value = errorText(e)
  } finally {
    statsLoading.value = false
  }
}

/** 参数摘要可能很长（服务端留了 1000 字），列表里只看开头一段。 */
function argsText(preview: string): string {
  return preview.length > 120 ? `${preview.slice(0, 120)}…` : preview
}

let filterTimer: ReturnType<typeof setTimeout> | undefined
watch(skillKeyword, () => {
  if (filterTimer) clearTimeout(filterTimer)
  filterTimer = setTimeout(loadStats, FILTER_DEBOUNCE_MS)
})

onMounted(() => {
  void loadStats()
  void loadEvents()
})

onUnmounted(() => {
  if (filterTimer) clearTimeout(filterTimer)
})
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">Skill 调用统计</h1>
        <p class="sm-sub">
          哪些 Skill 真的在用、用了多少次、最近什么时候用的。调用记录由 Agent
          在本机上报，这里只做汇总与查看。
        </p>
      </div>
    </div>

    <p v-if="statsError" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ statsError }}
    </p>

    <!-- 筛选：时间范围与 Skill 名。改了条件就重新统计。 -->
    <div class="sm-toolbar">
      <select
        v-model="days"
        class="sm-select sm-scope-select"
        aria-label="统计时间范围"
        data-test="days-filter"
        @change="loadStats"
      >
        <option v-for="d in DAY_OPTIONS" :key="d" :value="d">近 {{ d }} 天</option>
      </select>
      <input
        v-model="skillKeyword"
        class="sm-input sm-search"
        type="search"
        placeholder="按 Skill 名过滤（前缀匹配）"
        aria-label="按 Skill 名过滤"
        data-test="skill-filter"
      />
    </div>

    <!-- 概览：三个数字，不画图。人数是全局去重的，不是各 Skill 相加。 -->
    <div class="sm-stat-row sm-mb-16">
      <div class="sm-panel sm-stat">
        <p class="sm-stat-label">总调用次数</p>
        <p class="sm-stat-value sm-num" data-test="total-calls">
          {{ pending ? '—' : (summary?.total_calls ?? 0) }}
        </p>
      </div>
      <div class="sm-panel sm-stat">
        <p class="sm-stat-label">涉及 Skill 数</p>
        <p class="sm-stat-value sm-num" data-test="skill-count">
          {{ pending ? '—' : (summary?.skill_count ?? 0) }}
        </p>
      </div>
      <div class="sm-panel sm-stat">
        <p class="sm-stat-label">涉及人数</p>
        <p class="sm-stat-value sm-num" data-test="user-count">
          {{ pending ? '—' : (summary?.user_count ?? 0) }}
        </p>
      </div>
    </div>

    <!-- 汇总表：哪个 Skill 被人用得最多。 -->
    <div class="sm-panel sm-mb-16">
      <div class="sm-panel-head">
        <h2 class="sm-section">按 Skill 汇总</h2>
        <span class="sm-meta">调用次数多的在前</span>
      </div>

      <div v-if="statsLoading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!statsError && !stats.length"
        text="这个范围内还没有调用记录。Skill 真被调用过之后会出现在这里。"
      />

      <div v-else class="sm-table-scroll">
        <table class="sm-table sm-table--flush sm-table--stack">
          <thead>
            <tr>
              <th>Skill</th>
              <th class="sm-num">调用次数</th>
              <th class="sm-num">使用人数</th>
              <th class="sm-num">最近调用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in stats" :key="row.skill" data-test="stat-row">
              <td class="sm-mono sm-break">{{ row.skill }}</td>
              <td class="sm-num">{{ row.call_count }}</td>
              <td class="sm-num">{{ row.user_count }}</td>
              <td class="sm-muted sm-num">{{ formatDateTime(row.last_used_at) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 流水：具体是哪一次调用。 -->
    <div class="sm-panel">
      <div class="sm-panel-head">
        <h2 class="sm-section">调用流水</h2>
        <span class="sm-muted sm-sm sm-num">{{ countText }}</span>
      </div>

      <p v-if="eventsError" class="sm-note sm-note--error sm-mb-12" data-test="events-error">
        {{ eventsError }}
      </p>

      <div v-if="eventsLoading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!eventsError && !events.length"
        text="还没有调用记录。Agent 真调用了 Skill 之后会出现在这里。"
      />

      <div v-else class="sm-list">
        <div
          v-for="(event, index) in events"
          :key="`${event.occurred_at}-${index}`"
          class="sm-item sm-item-static"
          data-test="usage-event"
        >
          <div class="sm-item-body">
            <div class="sm-item-head">
              <strong class="sm-item-title sm-mono">{{ event.skill }}</strong>
              <span v-if="event.agent" class="sm-muted sm-sm">{{ event.agent }}</span>
            </div>
            <p class="sm-muted sm-meta sm-break">
              {{ formatDateTime(event.occurred_at) }}
              <template v-if="event.project">
                · <code class="sm-mono">{{ event.project }}</code>
              </template>
            </p>
            <p
              v-if="event.args_preview"
              class="sm-muted sm-meta sm-break"
              :title="event.args_preview"
              data-test="event-args"
            >
              {{ argsText(event.args_preview) }}
            </p>
          </div>
        </div>
      </div>

      <!-- 还有下一页：说清还有，不假装这就是全部。 -->
      <div v-if="eventsHasMore" class="sm-row sm-mt-16">
        <button
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="load-more"
          :disabled="eventsLoadingMore"
          @click="loadMore"
        >
          {{ eventsLoadingMore ? '加载中…' : '加载更多' }}
        </button>
      </div>
    </div>
  </section>
</template>
