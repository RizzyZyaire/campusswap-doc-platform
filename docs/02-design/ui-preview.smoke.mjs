// =============================================================================
//  UI-PREVIEW.html（v2 · 河北师范大学口径）冒烟自检
//
//  覆盖四类最容易写错的东西：
//   ① Markdown 渲染（代码块/表格/引用/列表 —— v2 就栽在跨行正则上）
//   ② 路由守卫矩阵（哪些身份能进哪些页面，无权限必须落到 403）
//   ③ 权限驱动渲染（侧栏菜单随角色增减）
//   ④ 口径检查（校园数据是否就位、公司口径残留是否清零、主题与 CSS 是否一致）
//
//  用法（Node 18+，本机 F:\node\node.exe）：
//      node docs/02-design/ui-preview.smoke.mjs
//  退出码 = 失败数（0 = 全绿）。路径相对本文件解析。
//  该脚本是预览稿自检，不计入 docs/03-qa-review 的 9 个产品机检（420 项断言）。
// =============================================================================
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const FILE = join(dirname(fileURLToPath(import.meta.url)), 'UI-PREVIEW.html')
const html = readFileSync(FILE, 'utf8')
const js = html.match(/<script>([\s\S]*?)<\/script>/)[1]

/* ---------- 最小 DOM 打桩 ---------- */
function makeEl(id) {
  const cls = new Set()
  const attrs = {}
  return {
    id, textContent: '', innerHTML: '', className: '', value: '', type: 'password', disabled: false,
    classList: {
      add: (c) => cls.add(c), remove: (c) => cls.delete(c),
      toggle: (c, on) => { on === undefined ? (cls.has(c) ? cls.delete(c) : cls.add(c)) : (on ? cls.add(c) : cls.delete(c)) },
      contains: (c) => cls.has(c)
    },
    addEventListener: () => {},
    querySelectorAll: () => [],
    setAttribute: (k, v) => { attrs[k] = v },
    getAttribute: (k) => (k in attrs ? attrs[k] : null)
  }
}
const ids = ['nav', 'sideFoot', 'crumb', 'userChip', 'content', 'appShell', 'viewLogin',
  'pvRole', 'pvView', 'lgBtn', 'lgUser', 'lgPass', 'lgAlert', 'lgAlertMsg',
  'pwEye', 'ed', 'edPreview', 'themeBtn', 'themeFab', 'themeDots', 'fabDots', 'themeName', 'themePop']
const reg = new Map(ids.map((i) => [i, makeEl(i)]))
const document = {
  body: { id: 'body' },
  documentElement: { attrs: {}, setAttribute(k, v) { this.attrs[k] = v }, getAttribute(k) { return this.attrs[k] } },
  querySelectorAll: () => [],
  addEventListener: () => {},
  getElementById: (id) => reg.get(id) ?? null
}
const window = { scrollTo: () => {} }

const api = new Function('document', 'window', js + '\n;return window.__PREVIEW__;')(document, window)

/* ---------- 断言工具 ---------- */
let pass = 0, fail = 0
const t = (name, cond, detail) => {
  if (cond) { pass++; console.log(`[PASS] ${name}${detail ? ' -- ' + detail : ''}`) }
  else { fail++; console.log(`[FAIL] ${name}${detail ? ' -- ' + detail : ''}`) }
}
const divBalanced = (s) => (s.match(/<div\b/g) || []).length === (s.match(/<\/div>/g) || []).length

console.log('=== ① Markdown 渲染 ===')
const md = api.mdRender
t('标题', /<h1>接口规范<\/h1>/.test(md('# 接口规范')) && /<h2>二级<\/h2>/.test(md('## 二级')))
t('代码块（跨行）', /<pre><code>const a = 1\nconst b = 2<\/code><\/pre>/.test(md('```\nconst a = 1\nconst b = 2\n```')))
t('代码块不会吃掉后续段落', /<p>之后<\/p>/.test(md('```\ncode\n```\n\n之后')))
t('无序列表包在 ul 内', /<ul><li>甲<\/li>\s*<li>乙<\/li><\/ul>/.test(md('- 甲\n- 乙')))
t('引用', /<blockquote>注意<\/blockquote>/.test(md('> 注意')))
t('行内代码与加粗', /<code>x<\/code>/.test(md('`x`')) && /<b>粗<\/b>/.test(md('**粗**')))
t('表格', /<table><tr><td>阶段<\/td><td>时间<\/td><\/tr>/.test(md('| 阶段 | 时间 |\n| --- | --- |\n| 自查 | 9 月 |')))
t('上传占位符', /🖼 上传中 42%/.test(md('![上传中 42%](uploading)')))
t('HTML 被转义', /&lt;script&gt;/.test(md('<script>')))

console.log('=== ② 路由守卫矩阵 ===')
const allowed = {
  home: { staff: 1, docadmin: 1, admin: 1 },
  search: { staff: 1, docadmin: 1, admin: 1 },
  mine: { staff: 1, docadmin: 1, admin: 1 },
  edit: { staff: 1, docadmin: 1, admin: 1 },
  me: { staff: 1, docadmin: 1, admin: 1 },
  kit: { staff: 1, docadmin: 1, admin: 1 },
  review: { staff: 0, docadmin: 1, admin: 1 },
  governance: { staff: 0, docadmin: 1, admin: 1 },
  taxonomy: { staff: 0, docadmin: 1, admin: 1 },
  users: { staff: 0, docadmin: 0, admin: 1 },
  roles: { staff: 0, docadmin: 0, admin: 1 },
  org: { staff: 0, docadmin: 0, admin: 1 }
}
for (const role of ['staff', 'docadmin', 'admin']) {
  api.setRole(role)
  for (const [v, map] of Object.entries(allowed)) {
    api.go(v)
    const want = map[role] ? v : 'e403'
    t(`守卫 ${role} → ${v}`, api.getView() === want, `期望 ${want}，实际 ${api.getView()}`)
  }
}

console.log('=== ③ 侧栏菜单随权限增减 ===')
const navIds = (r) => api.navFor(r).reduce((a, g) => a.concat(g.items.map((i) => i.id)), [])
api.setRole('staff')
t('教职工：只有 5 项', JSON.stringify(navIds('staff')) === JSON.stringify(['home', 'search', 'mine', 'edit', 'me']), navIds('staff').join(','))
t('教职工：无审核/治理/分类', !navIds('staff').some((i) => ['review', 'governance', 'taxonomy'].includes(i)))
t('教职工：无任何管理菜单', !navIds('staff').some((i) => ['users', 'roles', 'org'].includes(i)))
t('教职工：有「我的资料」', navIds('staff').includes('me'))
api.setRole('docadmin')
t('文档管理员：+3 项治理类', navIds('docadmin').includes('review') && navIds('docadmin').includes('governance') && navIds('docadmin').includes('taxonomy'))
t('文档管理员：仍无管理菜单', !navIds('docadmin').some((i) => ['users', 'roles', 'org'].includes(i)))
api.setRole('admin')
t('系统管理员：全 11 项', navIds('admin').length === 11, navIds('admin').join(','))
t('管理菜单拆成三个独立项', ['users', 'roles', 'org'].every((i) => navIds('admin').includes(i)))
t('导航里不存在「系统管理」聚合名', !JSON.stringify(api.navFor('admin')).includes('系统管理'))

console.log('=== ④ 各视图渲染健康度 ===')
const views = ['home', 'search', 'detail', 'edit', 'mine', 'review', 'governance', 'taxonomy', 'users', 'roles', 'org', 'me', 'kit', 'e403']
/* base64 里天然会出现 NaN 这类字符组合，扫描前先把 data URI 抹掉 */
const stripData = (s) => s.replace(/data:[a-z/+]+;base64,[A-Za-z0-9+/=]+/g, 'DATAURI')
api.setRole('admin')
for (const v of views) {
  const out = v === 'e403' ? api.renderRaw(v, 'review') : api.render(v)
  t(`渲染 ${v}`, out.length > 300, out.length + ' 字符')
  t(`渲染 ${v} 无占位垃圾`, !/undefined|\[object Object\]|NaN/.test(stripData(out)))
  t(`渲染 ${v} div 配对`, divBalanced(stripData(out)), (stripData(out).match(/<div\b/g) || []).length + '/' + (stripData(out).match(/<\/div>/g) || []).length)
}

console.log('=== ⑤ 校园口径与数据 ===')
t('学校名称', api.DATA.school === '河北师范大学')
const cats = api.DATA.categories.map((c) => c.name).join(',')
t('分类是校园口径', ['党政公文', '教务教学', '科研学术', '学生工作', '人事人才', '财务资产', '后勤保障', '图书档案', '学院文档', '模板表单'].every((c) => cats.includes(c)))
const orgs = api.DATA.org.map((o) => o.children.join(',')).join(',')
t('机构含软件学院（官方全称）', orgs.includes('软件学院（网络教育学院）'))
t('机构含教务处/科技处/人事处/图书馆/档案馆', ['教务处', '科技处', '人事处', '图书馆', '档案馆'].every((u) => orgs.includes(u)))
t('院系数量 ≥ 25（官网 27 个院系）', api.DATA.org[1].children.length >= 25, String(api.DATA.org[1].children.length))
t('示例文档题材为校园公文', api.DATA.docs.some((d) => d.title.includes('国家自然科学基金')) && api.DATA.docs.some((d) => d.title.includes('实习支教')))
t('角色权限数 11/20/39', JSON.stringify(api.DATA.roles.map((r) => r.n)) === '[11,20,39]')
t('权限树 39 个权限点', (() => { let n = 0; const walk = (ns) => ns.forEach((x) => { n++; if (x.children) walk(x.children) }); walk(api.DATA.permTree); return n === 39 })())
/* 注意：设计系统页为了说明「演示数据待重新种子化」会引用旧口径原话，故排除 kit 页 */
const bizViews = views.filter((v) => v !== 'kit')
const all = bizViews.map((v) => (v === 'e403' ? api.renderRaw(v, 'review') : api.render(v))).join('')
t('公司口径残留清零（业务页 + 数据层）', !/前端开发|后端开发|JPA 实体建模规范|技术部|产品部|接口规范（v1）/.test(all + JSON.stringify(api.DATA)))
t('检索页只出现已发布文档', !/badge draft|badge trash|badge archived/.test(api.render('search')))
t('内容治理页标注接口缺口', /接口缺口/.test(api.render('governance')))
t('我的资料页标注自助改密缺口', /自助改密|PUT \/api\/auth\/password/.test(api.render('me')))
t('设计系统页列出后端差异清单', /演示数据要按校园口径重新种子化/.test(api.render('kit')))

console.log('=== ⑥ 主题与 CSS 一致性 ===')
t('6 套主题', api.THEMES.length === 6, api.THEMES.map((x) => x.name).join(' / '))
t('主题 id 唯一', new Set(api.THEMES.map((x) => x.id)).size === 6)
t('每套 5 个色卡', api.THEMES.every((x) => x.sw.length === 5))
t('每套都有说明', api.THEMES.every((x) => x.desc && x.desc.length > 8))
t('默认是师大蓝', api.THEMES[0].id === 'hebtu' && html.includes('data-theme="hebtu"'))
t('含暖色亮调主题（银杏暖）', api.THEMES.some((x) => x.id === 'gingko'))
t('含亮青绿主题（青瓷）', api.THEMES.some((x) => x.id === 'celadon'))
t('已删除松烟黛 / 宣纸暖', !api.THEMES.some((x) => ['dai', 'paper'].includes(x.id)) && !html.includes('data-theme="dai"') && !html.includes('data-theme="paper"'))
t('保留墨玉青 / 师大绛 / 墨夜', ['ink', 'jiang', 'night'].every((x) => api.THEMES.some((y) => y.id === x)))
t('每套主题都定义了非纯白页面底色', api.THEMES.every((x) => {
  const re = new RegExp('html\\[data-theme="' + x.id + '"]\\{([^}]*)\\}', 'g')
  let m, found = null
  while ((m = re.exec(html))) { const b = m[1].match(/--bg:\s*(#[0-9A-Fa-f]{6})/); if (b) found = b[1] }
  return !!found && found.toUpperCase() !== '#FFFFFF'
}))
t('右上角有可视化主题选择器', html.includes('id="themeBtn"') && html.includes('id="themePop"') && html.includes('class="theme-grid"'))
t('登录页有浮动换肤按钮', html.includes('id="themeFab"'))
t('主题选择器不再是文字下拉', !html.includes('id="pvTheme"') && !html.includes('id="themePick"'))
for (const th of api.THEMES) {
  t(`CSS 里有 [data-theme="${th.id}"]`, html.includes(`html[data-theme="${th.id}"]`))
}
const tokenCount = api.THEMES.map((th) => {
  const seg = html.split(`html[data-theme="${th.id}"]{`)[1] || ''
  return (seg.slice(0, seg.indexOf('}')).match(/--[a-z0-9-]+:/g) || []).length
})
t('三套新主题令牌数一致（≥24）', (() => { const n = ['hebtu', 'gingko', 'celadon'].map((id) => api.THEMES.findIndex((x) => x.id === id)).map((i) => tokenCount[i]); return new Set(n).size === 1 && n[0] >= 24 })(), tokenCount.join(','))
t('含深色主题', api.THEMES.some((x) => x.id === 'night'))

console.log('=== ⑦ 视觉资源（校徽 / 题字 / 风景） ===')
t('校徽已内联（data URI）', /<img class="brand-logo" src="data:image\/png;base64,/.test(html))
t('校训题字已内联', html.includes('alt="校训：怀天下 求真知"'))
t('校园风景已内联（横幅 + 登录页）', (html.match(/data:image\/jpeg;base64,/g) || []).length >= 2)
t('favicon 用校徽', /<link rel="icon" href="data:image\/png;base64,/.test(html))
t('没有残留 __ASSET_ 占位符', !html.includes('__ASSET_'))
t('没有「师」字假 Logo', !html.includes('<div class="brand-mark">师</div>'))

console.log('=== ⑧ 标记卫生 ===')
t('正文无内联 style 属性', !/\sstyle\s*=/.test(html))
t('无 style-xxx 占位属性', !/\sstyle-[a-z]/.test(html))
t('无外部资源引用', !/<script[^>]*src/i.test(html) && !/<link[^>]*href="https?:/i.test(html) && (html.match(/<img[^>]*src="(?!data:)/g) || []).length === 0)

console.log(`\nTOTAL pass=${pass} fail=${fail}`)
process.exit(fail === 0 ? 0 : 1)
