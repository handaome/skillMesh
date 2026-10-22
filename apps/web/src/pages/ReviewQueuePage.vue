<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, listReviews } from '../api'
import type { PendingReview } from '../types'
import { formatDateTime } from '../utils/format'
import EmptyState from '../components/EmptyState.vue'

const router = useRouter()
const reviews = ref<PendingReview[]>([])
const error = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    reviews.value = await listReviews()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}

function open(review: PendingReview) {
  router.push(`/reviews/${review.skill_id}/${review.version}`)
}

onMounted(load)
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-h1">待审版本</h1>
        <p class="sm-sub">团队与组织可见的版本不会直接发布，要在这里有人放行。</p>
      </div>
      <span class="sm-muted sm-text-sm sm-num">{{ reviews.length }} 个待审</span>
    </div>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-16"
    />
    <EmptyState
      v-else-if="!loading && !reviews.length"
      text="没有待审的版本"
    />

    <div v-else class="sm-list">
      <button
        v-for="review in reviews"
        :key="`${review.skill_id}-${review.version}`"
        class="sm-item sm-item-btn"
        data-test="review-item"
        @click="open(review)"
      >
        <div class="sm-grow">
          <div class="sm-item-head">
            <strong class="sm-truncate">{{ review.skill_name ?? '—' }}</strong>
            <code class="sm-mono sm-muted">{{ review.skill_slug ?? '—' }}</code>
            <span class="sm-pill">{{ review.version }}</span>
          </div>
          <p class="sm-muted sm-text-sm sm-truncate">
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
          :class="review.scan.status === 'passed' ? 'sm-pill-pos' : 'sm-pill-warn'"
        >扫描 {{ review.scan.status }}</span>
      </button>
    </div>
  </section>
</template>
