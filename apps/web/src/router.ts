import { createRouter, createWebHistory } from 'vue-router'
import { session } from './auth/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./pages/LoginPage.vue') },
    { path: '/invite/:token', component: () => import('./pages/InvitePage.vue') },
    { path: '/', component: () => import('./pages/MarketPage.vue') },
    { path: '/mine', component: () => import('./pages/MySkillsPage.vue') },
    { path: '/team', component: () => import('./pages/TeamPage.vue') },
    { path: '/reviews', component: () => import('./pages/ReviewQueuePage.vue') },
    { path: '/audit', component: () => import('./pages/AuditPage.vue') },
    { path: '/reviews/:skillId/:version', component: () => import('./pages/ReviewDetailPage.vue') },
  ],
})

// 没登录就去登录页，并记住原来想去哪
router.beforeEach((to) => {
  if (to.path === '/login' || to.path.startsWith('/invite/')) return true
  if (!session.accessToken) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  return true
})

export default router
