<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, approveVersion, getReviewDetail, rejectVersion } from '../api'
import type { ReviewDetail } from '../types'
import EmptyState from '../components/EmptyState.vue'

const route = useRoute()
const router = useRouter()

const detail = ref<ReviewDetail | null>(null)
const error = ref('')
const notice = ref('')
const busy = ref(false)

/**
 * 只有待审核的版本能过审。已过审/已拒绝的版本再点一次，服务端会回
 * 409 VERSION_CONFLICT——按钮干脆不显示，页面也不该让人点必然失败的按钮。
 */
const canDecide = computed(() => detail.value?.item.status === 'pending_review')

async function load() {
  error.value = ''
  try {
    detail.value = await getReviewDetail(String(route.params.skillId), String(route.params.version))
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
}

async function decide(approve: boolean) {
  if (!detail.value) return
  busy.value = true
  error.value = ''
  try {
    if (approve) {
      await approveVersion(detail.value.item.skill_id, detail.value.item.version)
      notice.value = '已通过，版本转为已发布。'
    } else {
      await rejectVersion(detail.value.item.skill_id, detail.value.item.version)
      notice.value = '已拒绝。'
    }
    await load()
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <button class="sm-btn sm-btn--quiet sm-btn--sm sm-mb-12" @click="router.push('/reviews')">
      ← 返回队列
    </button>

    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>
    <p v-if="notice" class="sm-note sm-note--success sm-mb-16" data-test="notice">
      {{ notice }}
    </p>

    <template v-if="detail">
      <div class="sm-title-row">
        <div>
          <h1 class="sm-title">{{ detail.item.skill_name ?? '—' }}</h1>
          <p class="sm-sub">
            提交人 {{ detail.item.submitted_by ?? '—' }}
            <template v-if="detail.item.source.repository">
              · {{ detail.item.source.repository }}
            </template>
            <template v-if="detail.item.source.ref">
              · {{ detail.item.source.ref }}
            </template>
            <template v-if="detail.item.source.commit">
              · <code class="sm-mono">{{ detail.item.source.commit.slice(0, 12) }}</code>
            </template>
          </p>
        </div>
        <div class="sm-row">
          <span class="sm-pill sm-mono">{{ detail.item.version }}</span>
          <span class="sm-pill" data-test="version-status">{{ detail.item.status }}</span>
        </div>
      </div>

      <!--
        决定按钮放在最上面：审版本的人来这里就是要放行或拒绝，
        证据在下面读，但按钮不该要滚到底才找得到。
      -->
      <div v-if="canDecide" class="sm-row sm-mb-16">
        <button class="sm-btn sm-btn--primary" data-test="approve" :disabled="busy" @click="decide(true)">
          通过并发布
        </button>
        <button class="sm-btn sm-btn--danger" data-test="reject" :disabled="busy" @click="decide(false)">
          拒绝
        </button>
      </div>
      <p v-else class="sm-muted sm-mb-16" data-test="not-decidable">
        该版本已不在待审核状态（当前：{{ detail.item.status }}），不需要再处理。
      </p>

      <div class="sm-panel sm-mb-16">
        <div class="sm-panel-head">
          <h2 class="sm-section">扫描结论</h2>
          <span class="sm-meta">{{ detail.item.scan.status }}</span>
        </div>
        <pre class="sm-pre">{{ detail.item.scan.summary || '无' }}</pre>
        <ul v-if="detail.findings.length" class="sm-list sm-mt-12">
          <li v-for="(finding, index) in detail.findings" :key="index" class="sm-sm">
            {{ finding.rule }} · {{ finding.path }}<template v-if="finding.line">:{{ finding.line }}</template>
            — {{ finding.detail }}
          </li>
        </ul>
      </div>

      <div class="sm-panel sm-mb-16">
        <div class="sm-panel-head">
          <h2 class="sm-section">文件差异</h2>
          <span class="sm-meta">
            {{ detail.diff.base_version ? `对比 ${detail.diff.base_version}` : '首个版本' }}
          </span>
        </div>
        <!--
          表格只在有数据时渲染：下面已经有一个「没有文件差异」的空态了，
          再挂一个「无数据」就是两个空态并排。
        -->
        <div v-if="detail.diff.files.length" class="sm-table-scroll">
          <table class="sm-table sm-table--flush">
            <thead>
              <tr>
                <th>文件</th>
                <th>变化</th>
                <th class="sm-num">行数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="file in detail.diff.files" :key="file.path">
                <td><code class="sm-mono" data-test="diff-row">{{ file.path }}</code></td>
                <td>{{ file.kind }}</td>
                <td class="sm-num">
                  <span v-if="file.kind === 'modified'">
                    <span v-if="file.truncated" class="sm-muted">差异较大，只报文件级</span>
                    <span v-else>+{{ file.added }} / -{{ file.removed }}</span>
                  </span>
                  <span v-else class="sm-muted">—</span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <EmptyState v-if="!detail.diff.files.length" text="没有文件差异" />
      </div>

      <div class="sm-panel">
        <div class="sm-panel-head">
          <h2 class="sm-section">SKILL.md</h2>
        </div>
        <pre class="sm-pre" data-test="skill-md">{{ detail.skill_md }}</pre>
      </div>
    </template>
  </section>
</template>
