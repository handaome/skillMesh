<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError, acceptInvitation, getInvitation } from '../api'
import { selectOrganization } from '../auth/currentOrg'
import { loadMe, session } from '../auth/session'
import type { InvitationSummary } from '../types'

const route = useRoute()
const router = useRouter()

const invitation = ref<InvitationSummary | null>(null)
const error = ref('')
const busy = ref(false)
const done = ref(false)

function errorText(e: unknown): string {
  return e instanceof ApiError ? `${e.code}: ${e.message}` : String(e)
}

async function load() {
  error.value = ''
  try {
    invitation.value = await getInvitation(String(route.params.token))
  } catch (e) {
    error.value = errorText(e)
  }
}

async function accept() {
  busy.value = true
  error.value = ''
  try {
    const accepted = await acceptInvitation(String(route.params.token))
    done.value = true
    // 刚加入的组织还不在 session.organizations 里，不刷新的话团队页会说
    // 「你还没有加入任何组织」。刷新失败不推翻「已接受」这个事实。
    try {
      await loadMe()
      selectOrganization(accepted.organization_id)
    } catch {
      // 会话没刷新，去团队页再说
    }
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card class="sm-login" shadow="never">
    <h1 class="sm-h1">加入团队</h1>

    <el-alert
      v-if="error"
      data-test="error"
      :title="error"
      type="error"
      :closable="false"
      show-icon
      class="sm-mb-12"
    />

    <template v-if="invitation">
      <p>
        组织 <strong>{{ invitation.organization_name }}</strong>
        <template v-if="invitation.team_name">
          · 团队 <strong>{{ invitation.team_name }}</strong>
        </template>
      </p>
      <p class="sm-muted">
        角色 {{ invitation.org_role }}<template v-if="invitation.team_name"> / {{ invitation.team_role }}</template>
      </p>
      <el-alert
        v-if="invitation.status !== 'pending'"
        data-test="invalid"
        :title="`这份邀请已经${invitation.status === 'expired' ? '过期' : '失效'}。`"
        type="warning"
        :closable="false"
        show-icon
        class="sm-mb-12"
      />

      <el-alert
        v-if="done"
        data-test="done"
        title="已加入，去团队页看看。"
        type="success"
        :closable="false"
        show-icon
        class="sm-mb-12"
      />

      <el-button
        v-if="!session.accessToken"
        type="primary"
        data-test="login-first"
        @click="router.push({ path: '/login', query: { redirect: route.fullPath } })"
      >登录后接受</el-button>

      <el-button
        v-else-if="invitation.status === 'pending' && !done"
        type="primary"
        :loading="busy"
        data-test="accept"
        @click="accept"
      >{{ busy ? '接受中' : '接受邀请' }}</el-button>

      <router-link v-if="done" to="/team">
        <el-button class="sm-mt-8">去团队页</el-button>
      </router-link>
    </template>
  </el-card>
</template>
