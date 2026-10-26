<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { acceptInvitation, getInvitation } from '../api'
import { selectOrganization } from '../auth/currentOrg'
import { loadMe, session } from '../auth/session'
import type { InvitationSummary } from '../types'
import { errorText } from '../utils/errorText'

const route = useRoute()
const router = useRouter()

const invitation = ref<InvitationSummary | null>(null)
const error = ref('')
const busy = ref(false)
const done = ref(false)

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
    // 刚加入的组织还不在 session.organizations 里，不刷新的话团队页会说
    // 「你还没有加入任何组织」。所以先刷新会话，再标记完成——
    // 否则「去团队页」提前可点，用户会看到「你还没有加入任何组织」一闪而过。
    // 刷新失败不推翻「已接受」这个事实。
    try {
      await loadMe()
      selectOrganization(accepted.organization_id)
    } catch {
      // 会话没刷新，去团队页再说
    }
    done.value = true
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="sm-login">
    <h1 class="sm-title">加入团队</h1>

    <p v-if="error" class="sm-note sm-note--error sm-mb-12" data-test="error">
      {{ error }}
    </p>

    <template v-if="invitation">
      <p class="sm-mb-12">
        组织 <strong>{{ invitation.organization_name }}</strong>
        <template v-if="invitation.team_name">
          · 团队 <strong>{{ invitation.team_name }}</strong>
        </template>
      </p>
      <p class="sm-muted sm-mb-16">
        角色 {{ invitation.org_role }}<template v-if="invitation.team_name"> / {{ invitation.team_role }}</template>
      </p>
      <p
        v-if="invitation.status !== 'pending'"
        class="sm-note sm-note--warning sm-mb-12"
        data-test="invalid"
      >
        这份邀请已经{{ invitation.status === 'expired' ? '过期' : '失效' }}。
      </p>

      <p v-if="done" class="sm-note sm-note--success sm-mb-12" data-test="done">
        已加入，去团队页看看。
      </p>

      <button
        v-if="!session.accessToken"
        class="sm-btn sm-btn--primary sm-btn--block sm-mb-8"
        data-test="login-first"
        @click="router.push({ path: '/login', query: { redirect: route.fullPath } })"
      >
        登录后接受
      </button>

      <button
        v-else-if="invitation.status === 'pending' && !done"
        class="sm-btn sm-btn--primary sm-btn--block"
        :disabled="busy"
        data-test="accept"
        @click="accept"
      >
        {{ busy ? '接受中' : '接受邀请' }}
      </button>

      <router-link v-if="done" to="/team" class="sm-btn sm-btn--block sm-mt-8">
        去团队页
      </router-link>
    </template>
  </div>
</template>
