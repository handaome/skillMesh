<script setup lang="ts">
import { ref } from 'vue'
import DetailPage from './components/DetailPage.vue'
import SearchPage from './components/SearchPage.vue'

// v1 不做登录界面：把 CLI 登录拿到的 access token 贴进来即可。
const STORAGE_KEY = 'skillmesh_token'

const token = ref(localStorage.getItem(STORAGE_KEY) ?? '')
const openId = ref<string | null>(null)

function saveToken() {
  localStorage.setItem(STORAGE_KEY, token.value)
}
</script>

<template>
  <header>
    <h1>SkillMesh</h1>
    <input
      v-model="token"
      placeholder="粘贴 CLI 的 access token"
      aria-label="access token"
      @change="saveToken"
    />
  </header>
  <DetailPage
    v-if="openId"
    :skill-id="openId"
    :token="token"
    @back="openId = null"
  />
  <SearchPage v-else :token="token" @open="openId = $event" />
</template>

<style>
body {
  font-family: system-ui, sans-serif;
  margin: 0;
  padding: 1.5rem;
  max-width: 60rem;
}
header {
  display: flex;
  gap: 1rem;
  align-items: center;
  margin-bottom: 1.5rem;
}
header h1 {
  font-size: 1.25rem;
  margin: 0;
}
header input {
  flex: 1;
  padding: 0.4rem 0.6rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font: inherit;
}
</style>
