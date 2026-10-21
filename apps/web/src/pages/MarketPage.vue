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
/** 版本表正在读。读失败不是「这个 Skill 没有版本」。 */
const versionsLoading = ref(false)

function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

/**
 * 搜索。
 *
 * 响应回来时输入框里的词已经变了，这份结果就丢掉：不丢的话列表显示的是旧词的
 * 结果，输入框里却是新词，用户会打开错的 Skill。
 * 回车键与搜索按钮走的是同一个入口（@keyup.enter="search"），两条路都算上了。
 */
async function search() {
  const q = query.value
  loading.value = true
  error.value = ''
  try {
    const page = await searchSkills(q)
    if (query.value !== q) return
    skills.value = page.items
    searched.value = true
  } catch (e) {
    if (query.value !== q) return
    skills.value = []
    error.value = errorText(e)
  } finally {
    if (query.value === q) loading.value = false
  }
}

/**
 * 打开详情。
 *
 * 响应回来时用户已经打开别的 Skill（或退回列表了），这份详情就丢掉：
 * 不丢的话地址上是 ?skill=B、卡片头却显示 A。
 */
async function open(id: string) {
  openId.value = id
  detail.value = null
  versions.value = []
  versionsLoading.value = false
  error.value = ''
  if (route.query.skill !== id) {
    await router.replace({ query: { ...route.query, skill: id } })
    // 换人了就别接着拉了。
    if (openId.value !== id) return
  }
  try {
    const skill = await getSkill(id)
    if (openId.value !== id) return
    detail.value = skill
    versionsLoading.value = true
    const page = await getVersions(id)
    if (openId.value !== id) return
    versions.value = page.items
  } catch (e) {
    if (openId.value !== id) return
    error.value = errorText(e)
  } finally {
    if (openId.value === id) versionsLoading.value = false
  }
}

function back() {
  openId.value = null
  detail.value = null
  versions.value = []
  versionsLoading.value = false
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
      <!--
        表格只在有数据时渲染：Element Plus 自带的「No Data」也是一句谎话
        （读失败不是没有版本），跟上面的错误条并排会把人读歪。
      -->
      <el-table v-if="!versionsLoading && versions.length > 0" :data="versions" size="small">
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
      <el-empty
        v-if="!versionsLoading && !error && !versions.length"
        description="还没有版本"
        :image-size="60"
      />
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
