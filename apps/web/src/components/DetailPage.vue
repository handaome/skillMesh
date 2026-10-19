<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ApiError, getSkill, getVersions } from '../api'
import type { Skill, SkillVersion } from '../types'

const props = defineProps<{ skillId: string; token: string }>()
const emit = defineEmits<{ back: [] }>()

const skill = ref<Skill | null>(null)
const versions = ref<SkillVersion[]>([])
const error = ref('')

onMounted(async () => {
  try {
    skill.value = await getSkill(props.skillId, props.token)
    versions.value = (await getVersions(props.skillId, props.token)).items
  } catch (e) {
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  }
})

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
</script>

<template>
  <section>
    <button data-test="back" class="back" @click="emit('back')">← 返回</button>

    <p v-if="error" data-test="error" class="error">{{ error }}</p>

    <template v-else-if="skill">
      <h2>{{ skill.name }}</h2>
      <p class="desc">{{ skill.description }}</p>
      <dl>
        <dt>slug</dt>
        <dd><code>{{ skill.slug }}</code></dd>
        <dt>可见性</dt>
        <dd>{{ skill.visibility }}</dd>
        <dt>标签</dt>
        <dd>{{ skill.tags.join('、') || '—' }}</dd>
      </dl>

      <h3>版本</h3>
      <table>
        <thead>
          <tr>
            <th>版本</th><th>通道</th><th>状态</th><th>commit</th><th>SHA256</th><th>大小</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="version in versions" :key="version.id" data-test="version-row">
            <td>{{ version.version }}</td>
            <td>{{ version.channel }}</td>
            <td>{{ version.status }}</td>
            <td><code>{{ version.source_commit?.slice(0, 8) ?? '—' }}</code></td>
            <td><code>{{ version.artifact?.sha256.slice(0, 12) ?? '—' }}</code></td>
            <td>{{ version.artifact ? formatSize(version.artifact.size_bytes) : '—' }}</td>
          </tr>
          <tr v-if="!versions.length"><td colspan="6">暂无版本</td></tr>
        </tbody>
      </table>
    </template>
  </section>
</template>

<style scoped>
.back {
  margin-bottom: 1rem;
  padding: 0.4rem 0.8rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  font: inherit;
  cursor: pointer;
}
.desc {
  color: #374151;
}
dl {
  display: grid;
  grid-template-columns: 5rem 1fr;
  gap: 0.25rem 1rem;
}
dt {
  color: #6b7280;
}
dd {
  margin: 0;
}
table {
  border-collapse: collapse;
  width: 100%;
  margin-top: 0.5rem;
}
th,
td {
  text-align: left;
  padding: 0.4rem 0.6rem;
  border-bottom: 1px solid #e5e7eb;
}
.error {
  color: #b91c1c;
}
</style>
