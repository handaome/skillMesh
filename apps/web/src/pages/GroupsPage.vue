<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listGroups } from '../api'
import type { Group } from '../types'
import { errorText } from '../utils/errorText'
import EmptyState from '../components/EmptyState.vue'

const groups = ref<Group[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    groups.value = await listGroups()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">技能集合</h1>
        <p class="sm-sub">
          命名的一组 Skill：团队把一套规范收成一个集合，新人一键装齐。
        </p>
      </div>
    </div>

    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>

    <div v-if="loading" class="sm-empty">加载中…</div>

    <EmptyState
      v-else-if="!error && !groups.length"
      text="还没有技能集合。用 skillmesh group create 建一个。"
    />

    <!-- v-for 不能和 v-else 同元素，用 template 包一层。 -->
    <template v-else>
      <div
        v-for="group in groups"
        :key="group.id"
        class="sm-panel sm-mb-16"
        data-test="group-card"
      >
        <div class="sm-panel-head">
          <h2 class="sm-section">{{ group.name }}</h2>
          <span class="sm-meta">
            {{ group.slug }} · 已装 {{ group.installed_count }}/{{ group.member_count }}
          </span>
        </div>

        <p v-if="group.description" class="sm-muted sm-meta">{{ group.description }}</p>

        <div class="sm-list">
          <div v-for="member in group.members" :key="member.id" class="sm-item">
            <div class="sm-item-body">
              <div class="sm-item-head">
                <strong class="sm-item-title">{{ member.name }}</strong>
                <code class="sm-mono sm-muted sm-meta">{{ member.slug }}</code>
              </div>
            </div>
            <span class="sm-pill" :class="member.installed ? 'sm-pill-pos' : 'sm-pill-warn'">
              {{ member.installed ? '已装' : '未装' }}
            </span>
          </div>
        </div>

        <!--
          装集合在命令行做：写 Agent 目录是本机的事，服务端管不了，
          浏览器里没有那个身份（跟发布走 CLI 是同一个道理）。
        -->
        <p class="sm-muted sm-meta sm-mt-16">
          装齐这个集合：<code class="sm-mono">skillmesh group install {{ group.slug }}</code>
        </p>
      </div>
    </template>
  </section>
</template>
