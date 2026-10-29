<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { createUser, listUsers, updateUserStatus } from '../api'
import { session } from '../auth/session'
import type { UserAccount } from '../types'
import { errorText } from '../utils/errorText'
import { formatDateTime } from '../utils/format'

const accounts = ref<UserAccount[]>([])
const loading = ref(false)
const error = ref('')
const formError = ref('')
const creating = ref(false)

const username = ref('')
const displayName = ref('')
const email = ref('')
const password = ref('')

/** 只有站点管理员能管账号。服务端也会拦一道，这里先别把表单摆出来。 */
const isAdmin = computed(() => session.user?.role === 'admin')

/** 用户名规则与服务端一致：字母开头，只允许字母、数字、下划线和连字符。 */
const USERNAME_PATTERN = /^[A-Za-z][A-Za-z0-9_-]{2,31}$/

async function load() {
  loading.value = true
  error.value = ''
  try {
    accounts.value = await listUsers()
  } catch (e) {
    error.value = errorText(e)
  } finally {
    loading.value = false
  }
}

async function create() {
  formError.value = ''
  const name = username.value.trim()
  if (!USERNAME_PATTERN.test(name)) {
    formError.value = '用户名要以字母开头，只能有字母、数字、下划线和连字符，长度 3-32'
    return
  }
  if (password.value.length < 8) {
    formError.value = '密码至少 8 位'
    return
  }
  creating.value = true
  try {
    await createUser({
      username: name,
      password: password.value,
      display_name: displayName.value.trim() || undefined,
      email: email.value.trim() || undefined,
    })
    username.value = ''
    displayName.value = ''
    email.value = ''
    password.value = ''
    await load()
  } catch (e) {
    formError.value = errorText(e)
  } finally {
    creating.value = false
  }
}

/** 禁用或启用。禁用后登不进来，账号与它名下的数据都留着。 */
async function toggleStatus(account: UserAccount) {
  error.value = ''
  const next = account.status === 'active' ? 'disabled' : 'active'
  try {
    await updateUserStatus(account.id, next)
    await load()
  } catch (e) {
    error.value = errorText(e)
  }
}

onMounted(() => {
  if (isAdmin.value) load()
})
</script>

<template>
  <section>
    <!-- 普通账号手动敲地址进来：别摆表单，直接说清楚。 -->
    <div v-if="!isAdmin" class="sm-panel">
      <p class="sm-note sm-note--error sm-mb-16" data-test="forbidden">
        只有站点管理员能管账号。
      </p>
    </div>

    <template v-else>
    <div class="sm-title-row">
      <div>
        <h1 class="sm-title">账号管理</h1>
        <p class="sm-sub">
          自助注册关着，账号从这里来。建出来的账号是普通用户，
          管理员身份不开放给这个入口。
        </p>
      </div>
    </div>

    <p v-if="error" class="sm-note sm-note--error sm-mb-16" data-test="error">
      {{ error }}
    </p>

    <div class="sm-panel sm-mb-16">
      <div class="sm-panel-head">
        <h2 class="sm-section">新建账号</h2>
        <span class="sm-meta">密码至少 8 位，建完把初始密码告诉本人</span>
      </div>
      <div class="sm-field-grid sm-mb-16">
        <div class="sm-field">
          <label for="account-username">用户名</label>
          <input
            id="account-username"
            v-model="username"
            class="sm-input"
            autocomplete="off"
            placeholder="zhangsan"
            data-test="account-username"
          />
        </div>
        <div class="sm-field">
          <label for="account-display-name">显示名</label>
          <input
            id="account-display-name"
            v-model="displayName"
            class="sm-input"
            placeholder="张三（留空就用用户名）"
            data-test="account-display-name"
          />
        </div>
        <div class="sm-field">
          <label for="account-email">邮箱（可选）</label>
          <input
            id="account-email"
            v-model="email"
            class="sm-input"
            type="email"
            autocomplete="off"
            data-test="account-email"
          />
        </div>
        <div class="sm-field">
          <label for="account-password">初始密码</label>
          <input
            id="account-password"
            v-model="password"
            class="sm-input"
            type="password"
            autocomplete="new-password"
            data-test="account-password"
          />
        </div>
      </div>

      <p v-if="formError" class="sm-note sm-note--error sm-mb-12" data-test="create-error">
        {{ formError }}
      </p>

      <div class="sm-toolbar">
        <button
          class="sm-btn sm-btn--primary"
          data-test="create-user"
          :disabled="creating"
          @click="create"
        >
          {{ creating ? '创建中' : '建号' }}
        </button>
      </div>
    </div>

    <div v-if="loading" class="sm-empty">加载中…</div>

    <div v-else-if="accounts.length" class="sm-table-scroll">
      <table class="sm-table sm-table--flush sm-table--stack">
        <thead>
          <tr>
            <th>账号</th>
            <th>显示名</th>
            <th>角色</th>
            <th>状态</th>
            <th class="sm-num">创建时间</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="account in accounts" :key="account.id" data-test="account-row">
            <td>
              <div class="sm-row">
                <span v-if="account.username" class="sm-mono" data-test="account-name">
                  {{ account.username }}
                </span>
                <span v-else class="sm-muted">外部账号</span>
                <span
                  v-if="account.role === 'admin'"
                  class="sm-pill sm-pill-pos"
                  data-test="account-admin"
                >管理员</span>
              </div>
            </td>
            <td class="sm-muted sm-cell-sub">{{ account.display_name ?? '—' }}</td>
            <td class="sm-muted">{{ account.role }}</td>
            <td>
              <span
                class="sm-pill"
                :class="account.status === 'active' ? 'sm-pill-pos' : 'sm-pill-neg'"
                data-test="account-status"
              >
                {{ account.status === 'active' ? '正常' : '已禁用' }}
              </span>
            </td>
            <td class="sm-muted sm-num">{{ formatDateTime(account.created_at) }}</td>
            <td>
              <!-- 不能改自己的状态：站点只剩一个管理员时，这一手等于把系统锁死。 -->
              <button
                v-if="account.id !== session.user?.id"
                class="sm-btn sm-btn--quiet sm-btn--sm"
                data-test="toggle-status"
                @click="toggleStatus(account)"
              >
                {{ account.status === 'active' ? '禁用' : '启用' }}
              </button>
              <span v-else class="sm-muted sm-meta">当前登录</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-else class="sm-empty">还没有账号。</div>
    </template>
  </section>
</template>
