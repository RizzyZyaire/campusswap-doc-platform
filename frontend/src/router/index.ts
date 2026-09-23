import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { useUiStore } from '@/stores/ui'

/**
 * 路由表（UI_UX_SPECIFICATION §6.1 逐行落地：13 条功能路由 + 2 条异常路由）。
 *
 * <p>`meta.perm` = 进入该页所需的权限码；守卫按 §1.3 判定表执行：
 * 公开路由放行（已登录跳工作台）→ 未登录跳登录页并带 redirect → 有 token 无用户信息先 fetchMe →
 * 权限不足落 `/403`（403 页会写明缺哪个权限点）。</p>
 */
const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true, blank: true, title: '登录' } },

  { path: '/', redirect: '/' + '' }, // 占位，真实重定向在下面按权限重写
  { path: '/workbench', name: 'workbench', component: () => import('@/views/workbench/WorkbenchView.vue'), meta: { title: '工作台' } },
  { path: '/docs', name: 'docs', component: () => import('@/views/document/DocumentListView.vue'), meta: { perm: PERM.docSearch, title: '文档检索' } },
  // 注意顺序：/docs/edit 必须写在 /docs/:id 之前，否则会被当成 id='edit'
  { path: '/docs/edit/:id?', name: 'docs-edit', component: () => import('@/views/document/DocumentEditView.vue'), meta: { title: '写文档' } },
  { path: '/docs/:id', name: 'docs-detail', component: () => import('@/views/document/DocumentDetailView.vue'), meta: { perm: PERM.docSearch, title: '文档详情' } },
  { path: '/my', name: 'my', component: () => import('@/views/document/MyDocumentView.vue'), meta: { perm: PERM.docMine, title: '我的文档' } },
  { path: '/review', name: 'review', component: () => import('@/views/review/ReviewView.vue'), meta: { perm: PERM.docReview, title: '待我审核' } },
  { path: '/governance', name: 'governance', component: () => import('@/views/admin/GovernanceView.vue'), meta: { perm: PERM.docManage, title: '内容治理' } },
  { path: '/taxonomy', name: 'taxonomy', component: () => import('@/views/admin/TaxonomyView.vue'), meta: { perm: PERM.docCategory, title: '分类与标签' } },
  { path: '/admin/users', name: 'admin-users', component: () => import('@/views/admin/UserAdminView.vue'), meta: { perm: PERM.sysUser, title: '用户管理' } },
  { path: '/admin/roles', name: 'admin-roles', component: () => import('@/views/admin/RoleAdminView.vue'), meta: { perm: PERM.sysRole, title: '角色与权限' } },
  { path: '/admin/org', name: 'admin-org', component: () => import('@/views/admin/OrgAdminView.vue'), meta: { perm: PERM.sysDept, title: '组织机构' } },
  { path: '/me', name: 'me', component: () => import('@/views/me/ProfileView.vue'), meta: { title: '我的资料' } },

  { path: '/403', name: 'forbidden', component: () => import('@/views/error/ForbiddenView.vue'), meta: { title: '无权限' } },
  { path: '/404', name: 'not-found', component: () => import('@/views/error/NotFoundView.vue'), meta: { blank: true, title: '页面不存在' } },
  { path: '/:pathMatch(.*)*', redirect: '/404' }
]

routes[1] = {
  path: '/',
  redirect: () => {
    // 按权限重定向（§1.2）：有 doc:search → /docs；否则有 doc:mine → /my；否则 → /403
    const user = useUserStore()
    if (user.hasPerm(PERM.docSearch)) return '/docs'
    if (user.hasPerm(PERM.docMine)) return '/my'
    return '/403'
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to) => {
  const user = useUserStore()
  const ui = useUiStore()

  // ① 公开路由：已登录则回工作台
  if (to.meta.public === true) {
    if (user.isLoggedIn && user.info) return { name: 'workbench' }
    return true
  }

  // ② 未登录：去登录页并记住来路
  if (!user.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // ③ 有 token 无用户信息（刷新页面）：先拉一次
  if (!user.info) {
    try {
      await user.fetchMe()
    } catch {
      user.clear()
      return { name: 'login', query: { redirect: to.fullPath } }
    }
  }

  // ④ 权限不足 → 403（把缺的权限点带过去，403 页要写明）
  const need = to.meta.perm as string | undefined
  if (need && !user.hasPerm(need)) {
    ui.warn(`缺少权限点 ${need}，已跳转到无权限页`)
    return { name: 'forbidden', query: { need, from: to.fullPath } }
  }

  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 河北师范大学 校内文档平台` : '河北师范大学 · 校内文档平台'
})

export default router
