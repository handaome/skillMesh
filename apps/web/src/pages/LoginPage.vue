<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { buildAuthorizeUrl, codeChallenge, randomVerifier } from '../auth/pkce'
import { completeLogin } from '../auth/session'

const route = useRoute()
const router = useRouter()
const error = ref('')
const busy = ref(false)

const VERIFIER_KEY = 'skillmesh_pkce_verifier'
const STATE_KEY = 'skillmesh_pkce_state'
/** 回来时浏览器只带 code 与 state，原路径得自己存一份。 */
const REDIRECT_KEY = 'skillmesh_login_redirect'

function redirectUri(): string {
  return `${window.location.origin}/login`
}

async function startLogin() {
  busy.value = true
  error.value = ''
  try {
    const verifier = randomVerifier()
    const state = randomVerifier()
    sessionStorage.setItem(VERIFIER_KEY, verifier)
    sessionStorage.setItem(STATE_KEY, state)
    sessionStorage.setItem(REDIRECT_KEY, (route.query.redirect as string | undefined) ?? '/')
    const challenge = await codeChallenge(verifier)
    window.location.href = buildAuthorizeUrl(redirectUri(), state, challenge)
  } catch (e) {
    error.value = String(e)
    busy.value = false
  }
}

onMounted(async () => {
  const code = route.query.code as string | undefined
  if (!code) return
  const state = route.query.state as string | undefined
  const expected = sessionStorage.getItem(STATE_KEY)
  if (!expected || state !== expected) {
    error.value = '登录状态校验失败，请重试'
    return
  }
  const verifier = sessionStorage.getItem(VERIFIER_KEY)
  if (!verifier) {
    error.value = '登录状态丢失，请重试'
    return
  }
  busy.value = true
  try {
    await completeLogin(code, verifier, redirectUri())
    const redirect =
      (route.query.redirect as string | undefined)
      ?? sessionStorage.getItem(REDIRECT_KEY)
      ?? '/'
    sessionStorage.removeItem(VERIFIER_KEY)
    sessionStorage.removeItem(STATE_KEY)
    sessionStorage.removeItem(REDIRECT_KEY)
    await router.replace(redirect)
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
})
</script>

<template>
  <div class="sm-login">
    <div class="sm-login-mark" />
    <h1 class="sm-h1">SkillMesh</h1>
    <p class="sm-muted sm-mb-16">
      Agent Skill 的注册中心：管理你自己的 Skill，把版本发给团队，在发布前审一道。
    </p>
    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-16"
    />
    <el-button
      type="primary"
      :loading="busy"
      data-test="login"
      class="sm-login-btn"
      @click="startLogin"
    >{{ busy ? '登录中' : '用团队账号登录' }}</el-button>
  </div>
</template>
