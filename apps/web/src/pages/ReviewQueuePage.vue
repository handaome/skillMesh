<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError, listReviews } from '../api'
import type { PendingReview } from '../types'

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
    <h1 class="sm-h1">待审版本</h1>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />
    <el-empty
      v-else-if="!loading && !reviews.length"
      description="没有待审的版本"
    />

    <div v-else class="sm-list">
      <el-card
        v-for="review in reviews"
        :key="`${review.skill_id}-${review.version}`"
        shadow="hover"
        class="sm-item"
        data-test="review-item"
        @click="open(review)"
      >
        <div class="sm-item-head">
          <strong>{{ review.skill_name ?? '—' }}</strong>
          <code class="sm-mono sm-muted">{{ review.skill_slug ?? '—' }}</code>
          <el-tag size="small">{{ review.version }}</el-tag>
          <el-tag
            size="small"
            :type="review.scan.status === 'passed' ? 'success' : 'warning'"
          >扫描 {{ review.scan.status }}</el-tag>
        </div>
        <p class="sm-muted">
          提交人 {{ review.submitted_by ?? '—' }}
          <template v-if="review.source.repository">
            · {{ review.source.repository }}
          </template>
        </p>
      </el-card>
    </div>
  </section>
</template>
