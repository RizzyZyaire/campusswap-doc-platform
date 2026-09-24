<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import logo from '@/assets/brand/logo.png'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import ThemePicker from '@/components/ThemePicker.vue'
import { PERM, PERM_LABEL } from '@/utils/perm'
import * as reviewApi from '@/api/review'

/**
 * 产品外壳：左侧栏（导航随权限增减）+ 顶栏（搜索 / 通知 / 身份 / 主题选择器）+ 内容区。
 * 类名沿用预览稿组件层，视觉与预览稿（v8.3）一致。
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const ui = useUiStore()

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

/** 直达下拉（预览稿预览条上那个「直达」的正式版）：只列当前身份有权限去的地方。 */
const jumpTo = ref('')
const jumpOptions = computed(() =>
  navGroups.value.flatMap((g) => g.items).map((i) => ({ to: i.to, label: `${i.label}（${i.to}）` }))
)

function onJump(): void {
  const to = jumpTo.value
  jumpTo.value = ''
  if (to) void router.push(to)
}

/** 「关于平台」弹窗（点左上角品牌区打开）。 */
const aboutOpen = ref(false)
/** 平台版本信息：全部是这份代码里的事实，不写"规划中"的话。 */
const ABOUT_FACTS = [
  { k: '平台', v: '河北师范大学 · 校内文档平台（课程大作业）' },
  { k: '前端', v: 'Vue 3 + TypeScript(strict) + Vite + Tailwind + Pinia + markdown-it' },
  { k: '后端', v: 'Java 17 + Spring Boot 4.1 + Spring Data JPA + MySQL 8 + Redis' },
  { k: '鉴权', v: '拦截器 + Redis 不透明 token + @RequiresPermission 注解切面（四道关卡）' },
  { k: '权限点', v: '39 个（三层：目录 / 菜单 / 按钮），3 个内置角色' },
  { k: '接口', v: '57 个端点，统一响应体 { code, message, data }，错误码与 HTTP 状态一致' },
  { k: '文档', v: 'Markdown 正文 + 图片附件（平台不处理 Office 格式，见 PRD 非目标 O7）' }
]

const bellOpen = ref(false)
const chipOpen = ref(false)
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
      <!-- 品牌区：可点（弹「关于平台」，含校徽原图与说明）。
           用户反馈：原来这里既不能点、又是可选中文本 —— 单击会留下打字用的竖虚线光标，很怪。
           所以做成按钮 + `user-select:none`（见 main.css 的 .brand 覆盖）。 -->
      <button class="brand" type="button" title="关于平台" @click.stop="aboutOpen = true">
        <img class="brand-logo" :src="logo" alt="河北师范大学校徽" />
        <div>
          <div class="brand-name">校内文档平台</div>
          <div class="brand-sub">HEBNU DOC PLATFORM</div>
        </div>
      </button>

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

        <!-- 右侧控件组：加 top-right（预览稿组件层里就是 margin-left:auto）整体贴右。
             用户反馈："主题切换虽然在其它功能右边，但还是居于页面中间" —— 根因就是这个 flex 行没贴右。 -->
        <div class="row top-right" @click.stop>
          <input
            v-model="keyword"
            class="input input-sm"
            placeholder="搜索文档标题、正文…"
            @keyup.enter="submitSearch"
          />

          <!-- 直达：预览条上那个「直达」下拉的正式版（按权限列出可去的地方，选完即跳） -->
          <select v-model="jumpTo" class="select select-compact" title="直达" @change="onJump">
            <option value="">直达…</option>
            <option v-for="p in jumpOptions" :key="p.to" :value="p.to">{{ p.label }}</option>
          </select>

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

          <!-- 主题选择器：**独立占顶栏最右**（用户要求：不要夹在通知与身份之间，要一眼看到）
               与登录页共用同一个组件，色卡颜色由 sync-preview 从预览稿生成 -->
          <span class="topbar-sep"></span>
          <ThemePicker />
        </div>
      </header>

      <main class="content">
        <RouterView />
      </main>
    </div>

    <!-- 关于平台（点左上角品牌区弹出；用户反馈"要么能点弹出原图或说明，要么就别做成可点的"） -->
    <div v-if="aboutOpen" class="modal-mask" @click.self="aboutOpen = false">
      <div class="modal about-modal">
        <div class="modal-hd">关于平台</div>
        <div class="modal-bd">
          <div class="about-hd">
            <img :src="logo" alt="河北师范大学校徽" class="about-crest" />
            <div>
              <div class="about-tt">河北师范大学 · 校内文档平台</div>
              <div class="about-ss">HEBEI NORMAL UNIVERSITY · HEBNU DOC PLATFORM</div>
            </div>
          </div>
          <div class="kv mt12">
            <template v-for="f in ABOUT_FACTS" :key="f.k">
              <div class="k">{{ f.k }}</div>
              <div>{{ f.v }}</div>
            </template>
          </div>
          <div class="hint mt12">
            校徽与校训（怀天下，求真知）取自学校公开形象素材，仅用于课程演示；平台内的人名、工号与文档均为虚构演示数据。
          </div>
        </div>
        <div class="modal-ft">
          <RouterLink class="btn" to="/workbench" @click="aboutOpen = false">回到工作台</RouterLink>
          <button class="btn btn-primary" @click="aboutOpen = false">知道了</button>
        </div>
      </div>
    </div>
  </div>
</template>
