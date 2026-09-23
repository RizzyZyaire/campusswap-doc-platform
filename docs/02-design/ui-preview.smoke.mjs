// =============================================================================
//  UI-PREVIEW.html 冒烟自检（Node，35 项断言）
//
//  为什么需要它：预览稿里有两处最容易写错的逻辑 —— ①路由守卫（按权限把无权限页面
//  拦到 403）②权限驱动渲染（菜单与按钮随身份增减）。用最小 DOM 打桩把内联脚本真跑
//  一遍，比"打开看一眼"可靠。
//
//  用法（Node 18+，本机 F:\node\node.exe）：
//      node docs/02-design/ui-preview.smoke.mjs
//  退出码 = 失败数（0 = 全绿）。路径相对本文件解析，可从任意目录调用。
//
//  注意：这是**预览稿**的自检，不属于 M0~M4 的 9 个产品机检脚本（那 9 个在
//  docs/03-qa-review/，共 420 项断言）。
// =============================================================================
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'UI-PREVIEW.html')
const html = readFileSync(FILE, 'utf8')
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1]

function makeEl(id) {
  const cls = new Set()
  return {
    id, textContent: '', className: '', value: '', type: 'password', disabled: false, innerHTML: '',
    classList: {
      add: (c) => cls.add(c),
      remove: (c) => cls.delete(c),
      toggle: (c, on) => { if (on === undefined) { cls.has(c) ? cls.delete(c) : cls.add(c) } else { on ? cls.add(c) : cls.delete(c) } },
      contains: (c) => cls.has(c)
    },
    addEventListener: () => {},
    getAttribute: () => null
  }
}

const ids = ['lgBtn', 'lgUser', 'lgPass', 'lgPassErr', 'loginAlert', 'loginAlertMsg', 'pwEye',
  'appShell', 'crumbText', 'userName', 'userAvatar', 'roleBadge', 'navRoleName', 'navPermCount',
  'docsCount', 'perm403Text', 'editorSrc', 'editorPreview', 'roleSel',
  'view-login', 'view-docs', 'view-detail', 'view-edit', 'view-my', 'view-review',
  'view-adminDocs', 'view-system', 'view-403', 'view-kit']
const reg = new Map(ids.map((i) => [i, makeEl(i)]))

const document = { querySelectorAll: () => [], getElementById: (id) => reg.get(id) ?? null }
const window = { scrollTo: () => {} }

const api = new Function('document', 'window', js + `
  ;return { go: go, applyPerms: applyPerms, setRole: function(r){ role = r; applyPerms(); }, getView: function(){ return view; }, has: has, VIEW_PERM: VIEW_PERM };
`)(document, window)

let pass = 0, fail = 0
const t = (name, cond, detail) => {
  if (cond) { pass++; console.log(`[PASS] ${name}${detail ? ' -- ' + detail : ''}`) }
  else { fail++; console.log(`[FAIL] ${name}${detail ? ' -- ' + detail : ''}`) }
}
const section = (id) => (html.match(new RegExp('<section id="' + id + '"[\\s\\S]*?</section>')) || [''])[0]

// 1) 守卫矩阵：每个身份能进哪些页面（权限集合取自库内 sys_role_permission 现查结果）
const expect = {
  staff:    { docs: true,  my: true,  review: false, adminDocs: false, system: false },
  docadmin: { docs: true,  my: true,  review: true,  adminDocs: true,  system: false },
  admin:    { docs: true,  my: true,  review: true,  adminDocs: true,  system: true  }
}
for (const role of Object.keys(expect)) {
  api.setRole(role)
  for (const view of Object.keys(expect[role])) {
    api.go(view)
    const want = expect[role][view]
    const got = api.getView()
    t(`guard ${role} -> ${view}`, want ? got === view : got === '403', `want=${want ? view : '403'} got=${got}`)
    if (!want) {
      t(`guard ${role} -> ${view} 提示缺失权限`, reg.get('perm403Text').textContent === '缺少权限：' + api.VIEW_PERM[view],
        reg.get('perm403Text').textContent)
    }
  }
}

// 2) 公开路由与骨架切换
api.setRole('staff'); api.go('login')
t('login 是公开路由', api.getView() === 'login')
t('login 时隐藏 app 骨架', reg.get('appShell').classList.contains('hidden'))
api.go('docs')
t('回到业务页恢复骨架', !reg.get('appShell').classList.contains('hidden'))

// 3) 身份切换后的文案与有效权限数（11 / 20 / 39 与 PRD §3.3 一致）
api.setRole('docadmin')
t('角色徽标', reg.get('roleBadge').textContent === 'DOC_ADMIN', reg.get('roleBadge').textContent)
t('有效权限数=20', reg.get('navPermCount').textContent === 20, String(reg.get('navPermCount').textContent))
t('用户名', reg.get('userName').textContent === '文档管理员', reg.get('userName').textContent)
api.setRole('staff')
t('staff 无审核权限', api.has('doc:search') && !api.has('doc:review'))
t('staff 有效权限数=11', reg.get('navPermCount').textContent === 11, String(reg.get('navPermCount').textContent))
api.setRole('admin')
t('admin 有 sys:role:grant', api.has('sys:role:grant'))
t('admin 有效权限数=39', reg.get('navPermCount').textContent === 39, String(reg.get('navPermCount').textContent))

// 4) 结构性断言：检索页只能出现已发布文档（后端 DocumentServiceImpl.search() 硬编码 PUBLISHED）
const docs = section('view-docs')
t('检索页无草稿徽标', !/badge-draft/.test(docs))
t('检索页无回收站徽标', !/badge-trash/.test(docs))
t('检索页无已归档徽标', !/badge-archived/.test(docs))
t('检索页仅 2 行结果', (docs.match(/<tr>/g) || []).length === 3, '含表头共 ' + (docs.match(/<tr>/g) || []).length + ' 行')
t('检索页声明接口只返回已发布', /只返回「已发布」文档/.test(docs))

// 5) 治理页必须如实标注「规格与后端不一致」这个已知缺口
const gov = section('view-adminDocs')
t('治理页标注规格与后端不一致', /规格与后端不一致/.test(gov) && /DocumentServiceImpl/.test(gov) && /ReviewServiceImpl/.test(gov))

console.log(`\nTOTAL pass=${pass} fail=${fail}`)
process.exit(fail === 0 ? 0 : 1)
