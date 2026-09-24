<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import logo from '@/assets/brand/logo.png'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { useThemeStore } from '@/stores/theme'
import { PERM, PERM_LABEL } from '@/utils/perm'
import * as reviewApi from '@/api/review'

/**
 * 产品外壳：左侧栏（导航随权限增减）+ 顶栏（主题 / 通知 / 身份框）+ 内容区。
 * 类名沿用预览稿组件层，视觉与 v8.2 预览稿一致。
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const ui = useUiStore()
const theme = useThemeStore()

interface NavItem {
  id: string
  label: string
  to: string
  perm?: string
  count?: number
}
interface NavGroup {
  title: string
  items: NavItem[]
}

/** 待审数量（有 doc:review 才拉；失败静默，不影响外壳可用）。 */
const pendingReview = ref(0)

const navGroups = computed<NavGroup[]>(() => {
  const groups: NavGroup[] = [
    {
      title: '工作',
      items: [
        { id: 'workbench', label: '工作台', to: '/workbench' },
        { id: 'docs', label: '文档检索', to: '/docs', perm: PERM.docSearch },
        { id: 'my', label: '我的文档', to: '/my', perm: PERM.docMine },
        { id: 'edit', label: '写文档', to: '/docs/edit', perm: PERM.docCreate }
      ]
    },
    {
      title: '内容管理',
      items: [
        { id: 'review', label: '待我审核', to: '/review', perm: PERM.docReview, count: pendingReview.value },
        { id: 'governance', label: '内容治理', to: '/governance', perm: PERM.docManage },
        { id: 'taxonomy', label: '分类与标签', to: '/taxonomy', perm: PERM.docCategory }
      ]
    },
    {
      title: '组织架构',
      items: [
        { id: 'users', label: '用户管理', to: '/admin/users', perm: PERM.sysUser },
        { id: 'roles', label: '角色与权限', to: '/admin/roles', perm: PERM.sysRole },
        { id: 'org', label: '组织机构', to: '/admin/org', perm: PERM.sysDept }
      ]
    },
    { title: '个人', items: [{ id: 'me', label: '我的资料', to: '/me' }] }
  ]
  return groups
    .map((g) => ({ title: g.title, items: g.items.filter((i) => !i.perm || user.hasPerm(i.perm)) }))
    .filter((g) => g.items.length > 0)
})

const pageTitle = computed(() => (route.meta.title as string | undefined) ?? '')

const bellOpen = ref(false)
const chipOpen = ref(false)
const themeOpen = ref(false)
const keyword = ref('')

/** 通知（后端暂无通知接口：静态三条 + 未读红点，口径与预览稿一致）。 */
const notices = ref([
  { id: 1, title: '系统将于 9 月 28 日 22:00–24:00 维护，期间不可提交审核', time: '今天 09:10', unread: true },
  { id: 2, title: '新版《公文格式规范》已发布，请各科室按新模板报送', time: '昨天 16:20', unread: true },
  { id: 3, title: '本学期文档审核时限调整为 3 个工作日', time: '09-19', unread: true }
])
const unreadCount = computed(() => notices.value.filter((n) => n.unread).length)

function readAll(): void {
  notices.value = notices.value.map((n) => ({ ...n, unread: false }))
}

function closePops(): void {
  bellOpen.value = false
  chipOpen.value = false
  themeOpen.value = false
}

function toggleThemePop(): void {
  const next = !themeOpen.value
  closePops()
  themeOpen.value = next
}

/** 选一套主题：换完顺手把面板收起来（预览稿也是点一下就生效并关闭）。 */
function pickTheme(id: string): void {
  theme.setTheme(id)
  themeOpen.value = false
  ui.ok(`已切换到「${theme.currentTheme.name}」`)
}

function submitSearch(): void {
  const kw = keyword.value.trim()
  void router.push({ name: 'docs', query: kw ? { keyword: kw } : {} })
}

async function onLogout(): Promise<void> {
  closePops()
  await user.logout()
  ui.ok('已退出登录')
  void router.replace({ name: 'login' })
}

onMounted(async () => {
  if (!user.hasPerm(PERM.docReview)) return
  try {
    const page = await reviewApi.reviewDocuments({ pageNum: 1, pageSize: 1, status: 'PUBLISHED' })
    pendingReview.value = page.total
  } catch {
    pendingReview.value = 0
  }
})

const permCount = computed(() => user.permissions.length)
const permTotal = Object.keys(PERM_LABEL).length
const permPreview = computed(() =>
  user.permissions
    .slice(0, 6)
    .map((c) => PERM_LABEL[c] ?? c)
    .join('、')
)
const roleText = computed(() => (user.roles.length ? user.roles.join(' / ') : '—'))
</script>

<template>
  <div class="app" @click="closePops">
    <aside class="sidebar">
      <div class="brand">
        <img class="brand-logo" :src="logo" alt="河北师范大学校徽" />
        <div>
          <div class="brand-name">校内文档平台</div>
          <div class="brand-sub">HEBNU DOC PLATFORM</div>
        </div>
      </div>

      <nav class="nav">
        <template v-for="group in navGroups" :key="group.title">
          <div class="nav-group">{{ group.title }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.id"
            class="nav-item"
            :class="{ active: route.path === item.to }"
            :to="item.to"
          >
            <span class="ico">•</span>
            <span>{{ item.label }}</span>
            <span v-if="item.count" class="cnt">{{ item.count }}</span>
          </RouterLink>
        </template>
      </nav>

      <div class="side-foot">
        <div>{{ user.info?.deptName ?? '—' }}</div>
        <div>{{ user.info?.realName ?? '—' }} · {{ roleText }}</div>
        <div>有效权限 {{ permCount }} / {{ permTotal }}</div>
      </div>
    </aside>

    <div class="main">
      <header class="topbar">
        <div class="crumb">
          <b>{{ pageTitle }}</b>
        </div>

        <div class="row" @click.stop>
          <input
            v-model="keyword"
            class="input input-sm"
            placeholder="搜索文档标题、正文…"
            @keyup.enter="submitSearch"
          />

          <!-- 主题选择器：与预览稿「通用：主题选择器（右上角，可视化色卡）」逐字同构 ——
               按钮上是当前主题的三色点 + 中文名 + ▾，弹出的是 6 张「迷你界面」色卡。
               样式来自 components.css（.themepick/.theme-btn/.dots/.theme-pop/.theme-grid/.tp），
               每张卡的颜色由 .t-<主题id> 上的 --c1~--c5 给出（由 sync-preview 从预览稿生成）。 -->
          <div class="themepick">
            <button class="btn theme-btn" title="切换主题（6 套，即时生效）" @click="toggleThemePop">
              <span class="dots" :class="`t-${theme.current}`"><i></i><i></i><i></i></span>
              <span>{{ theme.currentTheme.name }}</span>
              <span class="t3">▾</span>
            </button>
            <div v-if="themeOpen" class="theme-pop">
              <h4>选择主题（{{ theme.themes.length }} 套 · 即时生效 · 记住本次选择）</h4>
              <div class="theme-grid">
                <div
                  v-for="t in theme.themes"
                  :key="t.id"
                  class="tp"
                  :class="[`t-${t.id}`, { on: t.id === theme.current }]"
                  :title="t.desc"
                  @click="pickTheme(t.id)"
                >
                  <div class="mini">
                    <div class="sb"><i></i><i></i><i></i></div>
                    <div class="ct"><i class="w70"></i><i></i><i class="w50"></i></div>
                  </div>
                  <div class="cap">
                    <b>{{ t.name }}</b>
                    <span class="xs t3">{{ t.tag }}</span>
                    <span class="sw"><i></i><i></i><i></i><i></i><i></i></span>
                    <span v-if="t.id === theme.current" class="ok">✓</span>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="pop-host">
            <button class="btn btn-sm btn-ghost" title="通知" @click="bellOpen = !bellOpen; chipOpen = false">
              <span v-if="unreadCount" class="dot"></span>通知
            </button>
            <div v-if="bellOpen" class="pop-panel">
              <div class="pop-head">
                <b>通知</b>
                <span class="xs t3">{{ unreadCount }} 条未读</span>
                <span class="spacer"></span>
                <button v-if="unreadCount" class="link xs" @click="readAll">全部标为已读</button>
              </div>
              <div class="pop-body">
                <div v-for="n in notices" :key="n.id" class="note-item" :class="{ unread: n.unread }">
                  <div class="bd">
                    <div class="t">{{ n.title }}</div>
                    <div class="m">{{ n.time }}</div>
                  </div>
                </div>
              </div>
            </div>
          </div>

          <div class="pop-host">
            <button class="btn btn-sm" @click="chipOpen = !chipOpen; bellOpen = false">
              {{ user.info?.realName ?? '未登录' }}
            </button>
            <div v-if="chipOpen" class="pop-panel narrow">
              <div class="pop-head"><b>身份</b></div>
              <div class="pop-body">
                <div class="pop-sec">
                  {{ user.info?.realName }} · {{ roleText }}<br />
                  {{ user.info?.deptName }} · {{ user.info?.username }}<br />
                  有效权限 {{ permCount }} / {{ permTotal }}：{{ permPreview }}
                </div>
                <RouterLink class="pop-item" to="/me" @click="closePops">我的资料</RouterLink>
                <button class="pop-item" @click="onLogout">切换账号（退出后重新登录）</button>
                <button class="pop-item danger" @click="onLogout">退出登录</button>
              </div>
            </div>
          </div>
        </div>
      </header>

      <main class="content">
        <RouterView />
      </main>
    </div>
  </div>
</template>
