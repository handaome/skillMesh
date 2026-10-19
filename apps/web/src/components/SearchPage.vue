<script setup lang="ts">
import { ref } from 'vue'
import { ApiError, searchSkills } from '../api'
import type { Skill } from '../types'

const props = defineProps<{ token: string }>()
const emit = defineEmits<{ open: [id: string] }>()

const query = ref('')
const skills = ref<Skill[]>([])
const error = ref('')
const loading = ref(false)
const searched = ref(false)

async function search() {
  loading.value = true
  error.value = ''
  try {
    const page = await searchSkills(query.value, props.token)
    skills.value = page.items
    searched.value = true
  } catch (e) {
    skills.value = []
    error.value = e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <section class="search">
    <form @submit.prevent="search">
      <input v-model="query" placeholder="搜索 Skill 名称、描述或标签" />
      <button type="submit" :disabled="loading">{{ loading ? '搜索中…' : '搜索' }}</button>
    </form>

    <p v-if="error" data-test="error" class="error">{{ error }}</p>

    <p v-else-if="searched && !skills.length" class="empty">没有匹配的 Skill</p>

    <ul v-else class="list">
      <li
        v-for="skill in skills"
        :key="skill.id"
        data-test="skill-item"
        class="item"
        tabindex="0"
        @click="emit('open', skill.id)"
        @keydown.enter="emit('open', skill.id)"
      >
        <strong>{{ skill.name }}</strong>
        <code>{{ skill.slug }}</code>
        <span class="badge">{{ skill.visibility }}</span>
        <p class="desc">{{ skill.description }}</p>
        <p class="meta">最新版本 {{ skill.latest_version ?? '—' }}</p>
      </li>
    </ul>
  </section>
</template>

<style scoped>
form {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
input {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font: inherit;
}
button {
  padding: 0.5rem 1rem;
  border: 0;
  border-radius: 6px;
  background: #2563eb;
  color: #fff;
  font: inherit;
  cursor: pointer;
}
button:disabled {
  opacity: 0.6;
  cursor: progress;
}
.list {
  list-style: none;
  padding: 0;
  display: grid;
  gap: 0.75rem;
}
.item {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  cursor: pointer;
}
.item:hover,
.item:focus-visible {
  border-color: #2563eb;
  outline: none;
}
.desc {
  margin: 0.25rem 0;
  color: #374151;
}
.meta {
  margin: 0;
  color: #6b7280;
  font-size: 0.85rem;
}
.badge {
  margin-left: 0.5rem;
  font-size: 0.75rem;
  background: #eef2ff;
  color: #3730a3;
  padding: 0.1rem 0.4rem;
  border-radius: 4px;
}
.error {
  color: #b91c1c;
}
.empty {
  color: #6b7280;
}
</style>
