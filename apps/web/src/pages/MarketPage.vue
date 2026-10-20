<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, getSkill, getVersions, searchSkills } from '../api'
import type { Skill, SkillVersion } from '../types'

const route = useRoute()
const router = useRouter()

const query = ref('')
const skills = ref<Skill[]>([])
const error = ref('')
const loading = ref(false)
const searched = ref(false)

const openId = ref<string | null>(null)
const detail = ref<Skill | null>(null)
const versions = ref<SkillVersion[]>([])

function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function search() {
  loading.value = true
  error.value = ''
  try {
    const page = await searchSkills(query.value)
    skills.value = page.items
    searched.value = true
  } catch (e) {
    skills.value = []
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function open(id: string) {
  openId.value = id
  detail.value = null
  versions.value = []
  error.value = ''
  if (route.query.skill !== id) {
    await router.replace({ query: { ...route.query, skill: id } })
  }
  try {
    detail.value = await getSkill(id)
    versions.value = (await getVersions(id)).items
  } catch (e) {
    error.value = errorText(e)
  }
}

function back() {
  openId.value = null
  detail.value = null
  versions.value = []
  error.value = ''
  const next = { ...route.query }
  delete next.skill
  router.replace({ query: next })
}

// 首次进来地址上就带着 ?skill=<id> 时直接进详情，之后跟着查询参数走。
watch(
  () => route.query.skill,
  (value) => {
    if (typeof value === 'string' && value !== openId.value) open(value)
  },
  { immediate: true },
)
</script>

<template>
  <el-alert
    v-if="error"
    data-test="error"
    :title="error"
    type="error"
    :closable="false"
    show-icon
    class="sm-mb-12"
  />

  <el-card v-if="openId" shadow="never">
    <el-button text data-test="back" @click="back">← 返回</el-button>
    <template v-if="detail">
      <h1 class="sm-h1">{{ detail.name }}</h1>
      <p class="sm-mono sm-muted">{{ detail.slug }}</p>
      <p>{{ detail.description }}</p>
      <p>
        <el-tag size="small">{{ detail.visibility }}</el-tag>
        <el-tag size="small" type="info" class="sm-ml-4">最新 {{ detail.latest_version ?? '—' }}</el-tag>
      </p>
      <h2 class="sm-h2 sm-mt-24">版本</h2>
      <el-table :data="versions" size="small">
        <el-table-column prop="version" label="版本" width="120" />
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column prop="channel" label="通道" width="100" />
        <el-table-column label="提交" min-width="140">
          <template #default="scope">
            <span class="sm-mono">{{ scope.row.source_commit ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="140">
          <template #default="scope">
            <span class="sm-mono">{{ scope.row.artifact?.sha256.slice(0, 12) ?? '—' }}</span>
          </template>
        </el-table-column>
      </el-table>
    </template>
  </el-card>

  <section v-else>
    <div class="sm-search">
      <el-input
        v-model="query"
        class="sm-grow"
        placeholder="搜索 Skill 名称、描述或标签"
        clearable
        @keyup.enter="search"
      />
      <el-button type="primary" :loading="loading" data-test="search" @click="search">
        搜索
      </el-button>
    </div>

    <el-empty
      v-if="!error && searched && !skills.length"
      description="没有匹配的 Skill"
    />

    <div v-else-if="!error" class="sm-list">
      <el-card
        v-for="skill in skills"
        :key="skill.id"
        shadow="hover"
        class="sm-item"
        data-test="skill-item"
        @click="open(skill.id)"
      >
        <div class="sm-item-head">
          <strong>{{ skill.name }}</strong>
          <code class="sm-mono sm-muted">{{ skill.slug }}</code>
          <el-tag size="small">{{ skill.visibility }}</el-tag>
        </div>
        <p class="sm-muted">{{ skill.description }}</p>
        <p class="sm-muted sm-text-sm">最新版本 {{ skill.latest_version ?? '—' }}</p>
      </el-card>
    </div>
  </section>
</template>
