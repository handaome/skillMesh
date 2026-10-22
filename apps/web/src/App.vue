<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Box, Grid, Menu, Tickets, User } from '@element-plus/icons-vue'
import { currentOrg, selectOrganization } from './auth/currentOrg'
import { ensureFresh, loadMe, logoutEndingSession, meError, session } from './auth/session'

const router = useRouter()

/** 顶栏的组织选择器。选了谁，团队页就跟谁。 */
const orgId = computed({
  get: () => currentOrg.value?.id ?? '',
  set: (id: string) => selectOrganization(id),
})

/** 窄屏下侧栏收起来，点菜单键再展开。 */
const navOpen = ref(false)

/** 没登录时只留内容，不给侧栏与顶栏：登录页与邀请页自己占满整屏。 */
const signedIn = computed(() => Boolean(session.accessToken))

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
    // 失败原因已经记在 meError 里了，侧栏会显示
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
  // 主动登出要连 Keycloak 的 SSO 会话一起结束：只清本地的话，
  // 浏览器里的 SSO cookie 还在，再点「登录」会免密进成刚才那个人。
  logoutEndingSession()
}
</script>

<template>
  <div v-if="!signedIn" class="sm-bare">
    <router-view />
  </div>

  <div v-else class="app">
    <aside class="side" :class="{ open: navOpen }">
      <router-link class="side-brand" to="/" @click="navOpen = false">
        <span class="sm-mark" />
        SkillMesh
      </router-link>

      <!-- 工作空间切换器：你在哪个组织里看，团队页就跟着谁。 -->
      <el-select
        v-if="session.organizations.length"
        v-model="orgId"
        data-test="org-switch"
        size="small"
        class="sm-side-org"
      >
        <el-option
          v-for="o in session.organizations"
          :key="o.id"
          :label="o.name"
          :value="o.id"
        />
      </el-select>

      <nav class="side-nav">
        <router-link class="side-item" to="/" @click="navOpen = false">
          <el-icon><Grid /></el-icon>市场
        </router-link>
        <router-link class="side-item" to="/mine" @click="navOpen = false">
          <el-icon><Box /></el-icon>我的 Skill
        </router-link>
        <router-link class="side-item" to="/team" @click="navOpen = false">
          <el-icon><User /></el-icon>团队
        </router-link>
        <router-link class="side-item" to="/reviews" @click="navOpen = false">
          <el-icon><Tickets /></el-icon>审核
        </router-link>
      </nav>

      <div class="side-foot">
        <el-alert
          v-if="meError"
          data-test="me-error"
          :title="meError"
          type="error"
          :closable="false"
          show-icon
          class="sm-mb-8"
        />
        <div class="sm-side-user">
          <el-tag v-if="session.user" type="info" effect="plain" data-test="who">
            {{ session.user.display_name }}
          </el-tag>
          <el-button text data-test="logout" @click="signOut">登出</el-button>
        </div>
      </div>
    </aside>

    <div class="sm-col">
      <!-- 窄屏才出现的菜单条：侧栏收起来后，这里是唯一的入口。 -->
      <header class="sm-mobilebar">
        <el-button
          class="menu-toggle"
          text
          data-test="menu"
          aria-label="菜单"
          @click="navOpen = !navOpen"
        >
          <el-icon><Menu /></el-icon>
        </el-button>
        <span class="sm-mobilebar-title">SkillMesh</span>
      </header>

      <main class="sheet">
        <router-view />
      </main>
    </div>
  </div>
</template>
