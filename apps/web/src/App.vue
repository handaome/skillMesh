<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { currentOrg, selectOrganization } from './auth/currentOrg'
import { ensureFresh, loadMe, logoutEndingSession, meError, session } from './auth/session'
import AppIcon from './components/AppIcon.vue'
import CommandPalette from './components/CommandPalette.vue'

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

const NAV = [
  { to: '/', label: '市场', icon: 'market' as const },
  { to: '/mine', label: '我的 Skill', icon: 'box' as const },
  { to: '/team', label: '团队', icon: 'users' as const },
  { to: '/reviews', label: '审核', icon: 'review' as const },
  { to: '/groups', label: '集合', icon: 'layers' as const },
  { to: '/audit', label: '审计', icon: 'audit' as const },
  { to: '/accounts', label: '账号', icon: 'account' as const, adminOnly: true },
]

/** 账号管理只给站点管理员看：普通账号点进去只会吃一嘴 403。 */
const navItems = computed(() =>
  NAV.filter((item) => !item.adminOnly || session.user?.role === 'admin'),
)

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
  // 主动登出要连外部 IdP 的 SSO 会话一起结束：只清本地的话，
  // 浏览器里的 SSO cookie 还在，再点「登录」会免密进成刚才那个人。
  logoutEndingSession()
}
</script>

<template>
  <div v-if="!signedIn" class="sm-bare">
    <router-view />
  </div>

  <div v-else class="app">
    <!-- 命令面板：⌘K / Ctrl+K 打开，搜 Skill 或跳页面。 -->
    <CommandPalette />

    <aside class="side" :class="{ open: navOpen }">
      <router-link class="side-brand" to="/" @click="navOpen = false">
        <!-- 标记：四块拼成的网，取自 SkillMesh 的「网」。 -->
        <svg class="sm-mark" viewBox="0 0 20 20" aria-hidden="true">
          <rect x="1" y="1" width="7" height="7" rx="1.5" />
          <rect x="12" y="1" width="7" height="7" rx="1.5" />
          <rect x="1" y="12" width="7" height="7" rx="1.5" />
          <rect x="12" y="12" width="7" height="7" rx="1.5" fill="currentColor" />
        </svg>
        SkillMesh
      </router-link>

      <!-- 工作空间切换器：侧栏顶端，说清当前在看哪个组织 -->
      <select
        v-if="session.organizations.length"
        v-model="orgId"
        data-test="org-switch"
        class="sm-select sm-side-org"
        aria-label="当前组织"
      >
        <option
          v-for="o in session.organizations"
          :key="o.id"
          :value="o.id"
        >
          {{ o.name }}
        </option>
      </select>

      <nav class="side-nav">
        <router-link
          v-for="item in navItems"
          :key="item.to"
          class="side-item"
          :to="item.to"
          @click="navOpen = false"
        >
          <AppIcon class="sm-nav-icon" :name="item.icon" />
          {{ item.label }}
        </router-link>
      </nav>

      <div class="side-foot">
        <p v-if="meError" class="sm-note sm-note--error sm-mb-8" data-test="me-error">
          {{ meError }}
        </p>
        <div class="sm-side-user">
          <span class="sm-sm sm-muted" data-test="who">{{ session.user?.display_name }}</span>
          <button class="sm-btn sm-btn--quiet sm-btn--sm" data-test="logout" @click="signOut">
            登出
          </button>
        </div>
      </div>
    </aside>

    <div class="sm-col">
      <!-- 窄屏才出现的菜单条：侧栏收起来后，这里是唯一的入口。 -->
      <header class="sm-mobilebar">
        <button
          class="sm-btn sm-btn--quiet menu-toggle"
          data-test="menu"
          aria-label="菜单"
          @click="navOpen = !navOpen"
        >
          <AppIcon name="menu" />
        </button>
        <span class="sm-mobilebar-title">SkillMesh</span>
      </header>

      <main class="sheet">
        <router-view />
      </main>
    </div>
  </div>
</template>
