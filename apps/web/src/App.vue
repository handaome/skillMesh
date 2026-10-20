<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { currentOrg, selectOrganization } from './auth/currentOrg'
import { ensureFresh, loadMe, logout, meError, session } from './auth/session'

const router = useRouter()

/** 顶栏的组织选择器。选了谁，团队页就跟谁。 */
const orgId = computed({
  get: () => currentOrg.value?.id ?? '',
  set: (id: string) => selectOrganization(id),
})

/**
 * 拉一次 /me。失败要说出来——静默失败时团队页会对真正的组织成员说
 * 「你还没有加入任何组织」，那是谎话。
 *
 * force：重试时令牌可能已经过期（时钟不一致），不续期就拉，
 * 拿到的还是 401，永远好不了。
 */
async function refreshMe(force = false) {
  meError.value = ''
  if (force && !(await ensureFresh(true))) {
    // 续不了就登出（ensureFresh 已经清了会话），把人送回登录页
    router.push('/login')
    return
  }
  try {
    await loadMe()
  } catch {
    // 失败原因已经记在 meError 里了，顶栏会显示
  }
}

onMounted(async () => {
  if (!session.accessToken) return
  if (!(await ensureFresh())) {
    // 续不了就登出（ensureFresh 已经清了会话），把人送回登录页
    router.push('/login')
    return
  }
  await refreshMe()
})

function signOut() {
  logout()
  router.push('/login')
}
</script>

<template>
  <div class="sm-shell">
    <header class="sm-topbar">
      <router-link class="sm-brand" to="/">SkillMesh</router-link>
      <nav class="sm-nav">
        <router-link to="/">市场</router-link>
        <router-link to="/team">团队</router-link>
        <router-link to="/reviews">审核</router-link>
      </nav>
      <div class="sm-topbar-right">
        <el-button
          v-if="meError"
          text
          type="danger"
          data-test="me-error"
          :title="meError"
          @click="refreshMe(true)"
        >加载用户信息失败，点此重试</el-button>
        <el-select
          v-if="session.organizations.length"
          v-model="orgId"
          data-test="org-switch"
          size="small"
          class="sm-org-switch"
        >
          <el-option
            v-for="o in session.organizations"
            :key="o.id"
            :label="o.name"
            :value="o.id"
          />
        </el-select>
        <el-tag v-if="session.user" type="info" effect="plain" data-test="who">
          {{ session.user.display_name }}
        </el-tag>
        <el-button v-if="session.accessToken" text data-test="logout" @click="signOut">
          登出
        </el-button>
      </div>
    </header>
    <main class="sm-main">
      <router-view />
    </main>
  </div>
</template>
