<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { buildAuthorizeUrl, codeChallenge, loadPublicConfig, randomVerifier } from '../auth/pkce'
import { completeLogin, loginWithPassword, registerAccount } from '../auth/session'

const route = useRoute()
const router = useRouter()
const error = ref('')
const busy = ref(false)
/** 登录还是注册。服务端不允许自助注册时只留登录。 */
const mode = ref<'login' | 'register'>('login')
const username = ref('')
const password = ref('')
const displayName = ref('')
const email = ref('')
/** 服务端接了外部 IdP 才显示「用团队账号登录」。 */
const hasIdp = ref(false)
/** 服务端允许自助注册才显示「注册一个」。 */
const canRegister = ref(false)

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
    // 先取服务端的 OIDC 配置（issuer 与 client_id）。取不到就报错——
    // 拿写死的默认值凑，登录只会连到一个够不着的地址。
    await loadPublicConfig()
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

/** 内置账号的登录与注册。 */
async function submit() {
  busy.value = true
  error.value = ''
  try {
    if (mode.value === 'register') {
      await registerAccount({
        username: username.value.trim(),
        password: password.value,
        display_name: displayName.value.trim() || undefined,
        email: email.value.trim() || undefined,
      })
    } else {
      await loginWithPassword(username.value.trim(), password.value)
    }
    await router.replace((route.query.redirect as string | undefined) ?? '/')
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}

function toggleMode() {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}

onMounted(async () => {
  // 先看服务端配了什么：有没有外部 IdP、能不能自助注册。
  // 取不到就按「只有内置账号」处理——表单照样能用。
  try {
    const config = await loadPublicConfig()
    hasIdp.value = Boolean(config.issuer)
    canRegister.value = config.register_open
  } catch {
    // 忽略：下面的表单不依赖它
  }
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
    <!-- 标记与侧栏同一个：四块拼成的网。 -->
    <svg class="sm-login-mark" viewBox="0 0 20 20" aria-hidden="true">
      <rect x="1" y="1" width="7" height="7" rx="1.5" />
      <rect x="12" y="1" width="7" height="7" rx="1.5" />
      <rect x="1" y="12" width="7" height="7" rx="1.5" />
      <rect x="12" y="12" width="7" height="7" rx="1.5" fill="currentColor" />
    </svg>
    <h1 class="sm-title">SkillMesh</h1>
    <p class="sm-muted sm-mb-24">
      Agent Skill 的注册中心：管理你自己的 Skill，把版本发给团队，在发布前审一道。
    </p>
    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>

    <form class="sm-login-form" @submit.prevent="submit">
      <label class="sm-label" for="login-username">用户名</label>
      <input
        id="login-username"
        v-model="username"
        class="sm-input sm-mb-16"
        autocomplete="username"
        data-test="username"
      />
      <template v-if="mode === 'register'">
        <label class="sm-label" for="login-display-name">显示名（留空就用用户名）</label>
        <input
          id="login-display-name"
          v-model="displayName"
          class="sm-input sm-mb-16"
          data-test="display-name"
        />
        <label class="sm-label" for="login-email">邮箱（可选）</label>
        <input
          id="login-email"
          v-model="email"
          class="sm-input sm-mb-16"
          type="email"
          autocomplete="email"
          data-test="email"
        />
      </template>
      <label class="sm-label" for="login-password">密码</label>
      <input
        id="login-password"
        v-model="password"
        class="sm-input sm-mb-16"
        type="password"
        :autocomplete="mode === 'register' ? 'new-password' : 'current-password'"
        data-test="password"
      />
      <button
        class="sm-btn sm-btn--primary sm-btn--block"
        type="submit"
        :disabled="busy"
        data-test="submit"
      >
        {{ busy ? '处理中' : (mode === 'register' ? '注册并登录' : '登录') }}
      </button>
    </form>

    <p v-if="canRegister" class="sm-muted sm-mb-16">
      <button
        class="sm-btn sm-btn--quiet sm-btn--sm"
        type="button"
        data-test="toggle-mode"
        @click="toggleMode"
      >
        {{ mode === 'register' ? '已有账号？去登录' : '还没有账号？注册一个' }}
      </button>
    </p>

    <template v-if="hasIdp">
      <p class="sm-muted sm-mb-16">或者</p>
      <button
        class="sm-btn sm-btn--block"
        :disabled="busy"
        data-test="login"
        @click="startLogin"
      >
        {{ busy ? '登录中' : '用团队账号登录' }}
      </button>
    </template>
  </div>
</template>
