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

  // 根路径：**故意不具名**——它不是目的地，只是一个"按权限选落点"的入口。
  // 规格不变量是"13 条功能路由 + 2 条异常路由 = 15 个具名路由"（UI_UX_SPECIFICATION §6.1），
  // 具名就会被 verify-m5.ps1 的 D2a 数成第 16 个（2026-10-10 实测），所以落点判定走 to.path。
  { path: '/', component: () => import('@/views/workbench/WorkbenchView.vue'), meta: { title: '工作台' } },
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

/**
 * 根路径的按权限落点（UI_UX_SPECIFICATION §1.2）：有 `doc:search` → `/docs`；
 * 否则有 `doc:mine` → `/my`；都没有 → 403 页（带上缺的权限码）。
 *
 * <p><b>为什么放在守卫里而不是路由表的 redirect 回调里（2026-10-09 修 bug）</b>：
 * vue-router 的 redirect 回调在**任何守卫之前**执行，那时 store 里既没有 token 也没有权限，
 * 于是匿名访问 `/` 会被直接判成"无权限" → 返回 `/403` → 守卫把这次 `/403` 当成未登录的来路，
 * 拼出 `/login?redirect=/403` → 用户登录成功后 `router.replace('/403')`，**刚登录就掉进无权限页**。
 * 实测复现：匿名访问 `/` 得到 `?redirect=/403`，登录后落点 `/403`（应为 `/docs`）。
 * 放到守卫 ③ 之后，权限一定已由 login() 或 fetchMe() 填好，判定才是可信的。</p>
 *
 * @param user 用户 store
 * @returns 目标路由位置
 */
function landingByPermission(user: ReturnType<typeof useUserStore>) {
  if (user.hasPerm(PERM.docSearch)) return { name: 'docs' as const }
  if (user.hasPerm(PERM.docMine)) return { name: 'my' as const }
  return { name: 'forbidden' as const, query: { need: PERM.docSearch, from: '/' } }
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

  // ③.5 根路径：此时权限已就绪，按权限决定落点（见 landingByPermission 注释）
  if (to.path === '/') {
    return landingByPermission(user)
  }

  // ③.6 直接访问/浏览器恢复出来的 /403（没有 need 参数）：不要把人晾在无权限页。
  //      场景：上一次停在了 403 页，浏览器"继续上次会话"把 /403 原样恢复；
  //      或者用户把 403 页存了书签。这种情况没有"缺哪个权限"的信息，能进哪就进哪。
  //      （有 need 参数 = 守卫刚判定的结果，保持原样；landingByPermission 在真无权限时
  //        会带着 need 返回 forbidden，所以不会在这里来回打转。）
  if (to.name === 'forbidden' && !to.query.need) {
    return landingByPermission(user)
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
