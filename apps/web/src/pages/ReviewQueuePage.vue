<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listRecentReviews, listReviews } from '../api'
import { usePagedList } from '../composables/usePagedList'
import { SCAN_STATUS_TEXT } from '../constants/status'
import type { PendingReview } from '../types'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()

/**
 * 待审队列，按游标往下翻。
 *
 * 待审数没有上限：团队一直发版，队列就一直长。只显示一页的话，
 * 排在窗口外的待审版本永远没人看得见。
 */
const {
  items: reviews,
  loading,
  loadingMore,
  error,
  hasMore,
  countText,
  load,
  loadMore,
} = usePagedList<PendingReview>((_params, cursor) => listReviews('pending_review', cursor))

/** 最近放行的版本。只是背景信息，一次取一页即可。 */
const recent = ref<PendingReview[]>([])

/**
 * 最近放行的版本，同一个 Skill 只留最新的一版。
 * 一个 Skill 连着发五版时，五行重复的记录不如一行。
 */
function dedupe(items: PendingReview[]): PendingReview[] {
  const seen = new Set<string>()
  return items.filter((item) => {
    if (seen.has(item.skill_id)) return false
    seen.add(item.skill_id)
    return true
  })
}

/**
 * 取最近放行的版本。
 *
 * 取不到不算错：这一段只是背景，待办才是正事。
 */
async function loadRecent() {
  try {
    const page = await listRecentReviews('published')
    recent.value = dedupe(page.items)
  } catch {
    recent.value = []
  }
}

function open(review: PendingReview) {
  router.push(`/reviews/${review.skill_id}/${review.version}`)
}

onMounted(async () => {
  // 两块并行：待办要立刻看到，最近放行只是背景，晚一点到无妨。
  await Promise.all([load(), loadRecent()])
})
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">待审版本</h1>
        <p class="sm-sub">团队与组织可见的版本不会直接发布，要在这里有人放行。</p>
      </div>
      <span class="sm-muted sm-sm sm-num">{{ countText }}</span>
    </div>

    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>

    <div class="sm-panel sm-mb-16">
      <div class="sm-panel-head">
        <h2 class="sm-section">待放行</h2>
        <span class="sm-meta">按提交先后排列</span>
      </div>

      <div v-if="loading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!error && !reviews.length"
        text="没有待审的版本。团队与组织可见的新版本到了会在这里排队。"
      />

      <div v-else class="sm-list">
        <button
          v-for="review in reviews"
          :key="`${review.skill_id}-${review.version}`"
          class="sm-item sm-item-btn"
          data-test="review-item"
          @click="open(review)"
        >
          <div class="sm-item-body">
            <div class="sm-item-head">
              <strong class="sm-item-title sm-truncate">{{ review.skill_name ?? '—' }}</strong>
              <code v-if="review.skill_slug !== review.skill_name" class="sm-mono sm-muted sm-meta">
                {{ review.skill_slug }}
              </code>
              <span class="sm-pill sm-mono">{{ review.version }}</span>
            </div>
            <p class="sm-muted sm-meta sm-truncate">
              提交人 {{ review.submitted_by ?? '—' }}
              <template v-if="review.submitted_at">
                · 提交于 {{ formatDateTime(review.submitted_at) }}
              </template>
              <template v-if="review.source.repository">
                · {{ review.source.repository }}
              </template>
            </p>
          </div>
          <span
            class="sm-pill"
            :class="review.scan.status === 'passed' ? 'sm-pill-pos' : 'sm-pill-neg'"
          >
            <!-- 「待放行」列表里的药丸说的是扫描结论，不是审核结果：不带「扫描」二字，绿色的「通过」会被读成「审核已通过」。 -->
            扫描{{ SCAN_STATUS_TEXT[review.scan.status] ?? review.scan.status }}
          </span>
        </button>
      </div>

      <!-- 还有下一页：说清还有，不假装这就是全部。 -->
      <div v-if="hasMore" class="sm-row sm-mt-16">
        <button
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="load-more"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '加载中…' : '加载更多' }}
        </button>
      </div>
    </div>

    <!--
      最近放行的版本。待办空的时候这一段还在，页面不会是一张白纸：
      审核的人要能看见这台机器上确实在流转版本。
    -->
    <div v-if="recent.length" class="sm-panel">
      <div class="sm-panel-head">
        <h2 class="sm-section">最近放行</h2>
        <span class="sm-meta">已经发布的版本</span>
      </div>
      <div class="sm-list">
        <button
          v-for="review in recent"
          :key="`${review.skill_id}-${review.version}`"
          class="sm-item sm-item-btn"
          @click="open(review)"
        >
          <div class="sm-item-body">
            <div class="sm-item-head">
              <strong class="sm-item-title sm-truncate">{{ review.skill_name ?? '—' }}</strong>
              <code v-if="review.skill_slug !== review.skill_name" class="sm-mono sm-muted sm-meta">
                {{ review.skill_slug }}
              </code>
              <span class="sm-pill sm-mono">{{ review.version }}</span>
            </div>
            <p class="sm-muted sm-meta sm-truncate">
              提交人 {{ review.submitted_by ?? '—' }}
              <template v-if="review.submitted_at">
                · 提交于 {{ formatDateTime(review.submitted_at) }}
              </template>
            </p>
          </div>
          <span class="sm-pill sm-pill-pos">已发布</span>
        </button>
      </div>
    </div>
  </section>
</template>
