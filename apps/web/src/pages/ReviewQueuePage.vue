<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { batchDecide, listRecentReviews, listReviews } from '../api'
import { useMultiSelect } from '../composables/useMultiSelect'
import { usePagedList } from '../composables/usePagedList'
import { SCAN_STATUS_TEXT } from '../constants/status'
import type { BatchDecideResult, PendingReview } from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'
import ModalDialog from '../components/ModalDialog.vue'
import MultiSelectToolbar from '../components/MultiSelectToolbar.vue'

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

/** 一条待审版本的唯一键。 */
function reviewKey(review: PendingReview) {
  return `${review.skill_id}@${review.version}`
}

/**
 * 多选。
 *
 * 待审队列只有一个作用域（全部待审），scopeSignal 因此一直是常量；
 * 留着是为了接口一致——以后加了按团队筛选，切换作用域时要清空选择，
 * 否则留着上一批勾选就放错了对象。
 */
const filterSignal = ref('')
const scopeSignal = ref('')
const {
  selectedItems,
  selectedCount,
  isSelected,
  toggle,
  clear,
} = useMultiSelect<PendingReview>({
  items: reviews,
  filtered: reviews,
  getKey: reviewKey,
  filterSignal,
  scopeSignal,
})

/** 批量动作的结果：过了的和没过的分开报。 */
const batchResult = ref<BatchDecideResult | null>(null)
/** 批量动作执行中。 */
const batchBusy = ref(false)
/** 确认弹窗。批量放行是特权动作，不该一点就生效。 */
const confirmOpen = ref(false)
const pendingAction = ref<'approve' | 'reject'>('approve')

/**
 * 选中的条目里有没有能放行的 / 能驳回的。
 *
 * 能不能审由服务端算（can_approve / can_decide）：自己发布的版本不能自己放行，
 * 扫描阻断的也不能放行。按钮按这个显示，别让人点了必然 403。
 */
const canApproveSelected = computed(() => selectedItems.value.some((item) => item.can_approve))
const canRejectSelected = computed(() => selectedItems.value.some((item) => item.can_decide))

function askBatch(action: 'approve' | 'reject') {
  pendingAction.value = action
  confirmOpen.value = true
}

/**
 * 执行批量审核。
 *
 * 服务端逐条独立处理：过了的和没过的分开报。用户要看到的是
 * 「哪一条没过、为什么」，不是一个笼统的失败。
 */
async function runBatch() {
  confirmOpen.value = false
  batchBusy.value = true
  try {
    batchResult.value = await batchDecide(
      selectedItems.value.map((review) => ({
        skill_id: review.skill_id,
        version: review.version,
      })),
      pendingAction.value,
    )
    clear()
    await load()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    batchBusy.value = false
  }
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

    <!-- 批量结果：过了多少、没过多少、哪一条为什么没过。 -->
    <p
      v-if="batchResult"
      class="sm-note sm-mb-16"
      :class="batchResult.failed.length ? 'sm-note--warning' : 'sm-note--success'"
      data-test="batch-result"
    >
      {{ pendingAction === 'approve' ? '放行' : '拒绝' }}成功 {{ batchResult.succeeded.length }} 条<template
        v-if="batchResult.failed.length"
      >，{{ batchResult.failed.length }} 条没过：{{
        batchResult.failed.map((f) => `${f.version}（${f.message}）`).join('；')
      }}</template>
    </p>

    <div class="sm-panel sm-mb-16">
      <div class="sm-panel-head">
        <h2 class="sm-section">待放行</h2>
        <span class="sm-meta">按提交先后排列</span>
      </div>

      <!--
        批量工具条。待审数没有上限，逐条点开详情页放行是放不完的。
        「批量放行」要二次确认：放行即发布，团队成员就能装上这一版。
      -->
      <MultiSelectToolbar :count="selectedCount" :busy="batchBusy" @clear="clear">
        <button
          v-if="canApproveSelected"
          class="sm-btn sm-btn--primary sm-btn--sm"
          data-test="batch-approve"
          :disabled="batchBusy"
          @click="askBatch('approve')"
        >
          批量放行
        </button>
        <button
          v-if="canRejectSelected"
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="batch-reject"
          :disabled="batchBusy"
          @click="askBatch('reject')"
        >
          批量拒绝
        </button>
      </MultiSelectToolbar>

      <div v-if="loading" class="sm-empty">加载中…</div>

      <EmptyState
        v-else-if="!error && !reviews.length"
        text="没有待审的版本。团队与组织可见的新版本到了会在这里排队。"
      />

      <div v-else class="sm-list">
        <!--
          行本身不再整行是按钮：行里要放勾选框，按钮套按钮浏览器会把它拆开。
          左边那块仍然是按钮，点它打开详情。
        -->
        <div
          v-for="review in reviews"
          :key="`${review.skill_id}-${review.version}`"
          class="sm-item"
          :class="{ 'sm-item-on': isSelected(review) }"
        >
          <input
            class="sm-item-check"
            type="checkbox"
            :checked="isSelected(review)"
            :disabled="!review.can_decide"
            :aria-label="`选择 ${review.skill_name ?? review.skill_id} ${review.version}`"
            data-test="review-check"
            @change="toggle(review)"
          />
          <button class="sm-item-btn sm-grow" data-test="review-item" @click="open(review)">
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
          </button>
          <span
            class="sm-pill"
            :class="review.scan.status === 'passed' ? 'sm-pill-pos' : 'sm-pill-neg'"
          >
            <!-- 「待放行」列表里的药丸说的是扫描结论，不是审核结果：不带「扫描」二字，绿色的「通过」会被读成「审核已通过」。 -->
            扫描{{ SCAN_STATUS_TEXT[review.scan.status] ?? review.scan.status }}
          </span>
        </div>
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

    <!-- 批量动作的二次确认。 -->
    <ModalDialog
      :open="confirmOpen"
      :title="pendingAction === 'approve' ? '批量放行' : '批量拒绝'"
      @close="confirmOpen = false"
    >
      <p class="sm-mb-12">
        将{{ pendingAction === 'approve' ? '放行' : '拒绝' }} {{ selectedCount }} 个版本。<template
          v-if="pendingAction === 'approve'"
        >放行即发布，团队成员就能装上这一版。</template>
      </p>
      <template #footer>
        <button
          class="sm-btn sm-btn--quiet sm-btn--sm"
          data-test="batch-cancel"
          @click="confirmOpen = false"
        >
          取消
        </button>
        <button
          class="sm-btn sm-btn--primary sm-btn--sm"
          data-test="batch-confirm"
          @click="runBatch"
        >
          确定
        </button>
      </template>
    </ModalDialog>
  </section>
</template>
