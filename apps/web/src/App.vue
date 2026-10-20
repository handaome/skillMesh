<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { currentOrg, selectOrganization } from './auth/currentOrg'
import { ensureFresh, loadMe, logout, session } from './auth/session'

const router = useRouter()

/** 顶栏的组织选择器。选了谁，团队页就跟谁。 */
const orgId = computed({
  get: () => currentOrg.value?.id ?? '',
  set: (id: string) => selectOrganization(id),
})

onMounted(async () => {
  if (!session.accessToken) return
  if (!(await ensureFresh())) {
    // 续不了就登出（ensureFresh 已经清了会话），把人送回登录页
    router.push('/login')
    return
  }
  try {
    await loadMe()
  } catch {
    // 拉不到就留着空用户；登出按钮按 accessToken 渲染，用户还能手动登出
  }
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
