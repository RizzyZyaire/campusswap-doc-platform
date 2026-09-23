// =============================================================================
//  UI-PREVIEW.html（v8.2 · 河北师范大学口径）冒烟自检
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
//  该脚本是预览稿自检，不计入 docs/03-qa-review 的 9 个产品机检（504 项断言）。
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
    querySelector: () => null,
    querySelectorAll: () => [],
    setAttribute: (k, v) => { attrs[k] = v },
    getAttribute: (k) => (k in attrs ? attrs[k] : null)
  }
}
const ids = ['nav', 'sideFoot', 'crumb', 'userChip', 'content', 'appShell', 'viewLogin',
  'pvRole', 'pvView', 'lgBtn', 'lgUser', 'lgPass', 'lgAlert', 'lgAlertMsg',
  'pwEye', 'ed', 'edPreview', 'themeBtn', 'themeDots', 'themeName', 'themePop',
  'bellBtn', 'bellDot', 'bellPop', 'chipPop']
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
/* v5：三处「待办/缺口」标注已随后端三项变更清零，页面改为「已落地」表述 */
t('内容治理页标注接口已就位（编号 57）', /端点编号 57/.test(api.render('governance')) && /GET \/api\/documents\/manage/.test(api.render('governance')))
t('内容治理页不再出现「接口缺口」', !/接口缺口/.test(api.render('governance')))
t('我的资料页标注自助改密已落地（编号 56）', /端点编号 56/.test(api.render('me')) && /PUT \/api\/auth\/password/.test(api.render('me')))
t('我的资料页说明改密后全部会话失效', /全部会话立即失效/.test(api.render('me')))
t('设计系统页三条待办已打勾', (api.render('kit').match(/<span class="n">✓<\/span>/g) || []).length >= 4)
t('全文检索出参在检索页可见（highlight / matchedIn）', /class="hl"/.test(api.render('search')) && /<em>复制比<\/em>/.test(api.render('search')) && /matchedIn = content/.test(api.render('search')))
t('检索页排序含「相关度」（全文检索时可用）', /相关度 ↓（全文检索时）/.test(api.render('search')))
t('检索页筛选按 created_at 口径（创建时间，不再是更新时间）', /<label class="label">创建时间<\/label>/.test(api.render('search')))
t('预览不再出现「发文单位 / 提交单位」字样', !/发文单位|提交单位/.test(all + api.render('kit')))
t('数据层不再带 unit 字段（doc_document 无单位列）', !/unit:/.test(html))
t('登录下拉的所属单位与用户数据一致', ['系统管理员 · 信息化中心 · 赵慕辰', '文档管理员 · 信息化中心 · 王砚秋', '教职工 · 软件学院（网络教育学院） · 李承霖'].every((s) => html.includes(s)))
t('预览稿版本号统一为 v8.2（标题/页头/预览条）', (html.match(/界面预览稿 v8\.2/g) || []).length === 2 && html.includes('预览稿 v8.2 · 假数据')
  && !/界面预览稿 v[1-7]/.test(html.replace(/<!--[\s\S]*?-->/g, '')))
t('我的文档页不再有「单位」筛选', !/<label class="label">单位<\/label>/.test(api.render('mine')))

console.log('=== ⑤c v8：弹出面板 / 悬停到行 / 版式固定 / 主题名 ===')
/* 顶栏两个按钮必须真的弹东西（用户反馈"点了没有任何弹出"） */
api.setRole('admin')
t('通知铃有弹出面板与未读红点', html.includes('id="bellBtn"') && html.includes('id="bellPop"') && html.includes('id="bellDot"') && api.getUnreadNotes() === 3)
t('通知面板逐条列出未读并带时间', (() => { api.renderBellPop(); const el = document.getElementById('bellPop'); return /note-item/.test(el.innerHTML) && /系统将于/.test(el.innerHTML) && /维护/.test(el.innerHTML) })())
t('3 条通知全部标为未读', (api.renderBellPop() || document.getElementById('bellPop').innerHTML).toString() && ((document.getElementById('bellPop').innerHTML.match(/note-item unread/g) || []).length === 3))
t('通知可「全部标为已读」并清掉红点', (() => { api.setNotesRead(true); api.renderBellPop(); const el = document.getElementById('bellPop'); const ok = api.getUnreadNotes() === 0 && !/data-readall/.test(el.innerHTML) && (el.innerHTML.match(/note-item unread/g) || []).length === 0; api.setNotesRead(false); api.renderBellPop(); return ok })())
t('身份框有弹出菜单（介绍 + 我的资料 + 切换账号 + 退出登录）', (() => { api.renderChipPop(); const h = document.getElementById('chipPop').innerHTML; return /有效权限 39 \/ 39/.test(h) && /data-go="me"/.test(h) && /切换账号/.test(h) && /data-go="login"/.test(h) })())
/* v8.1：用户要求预览严格照正式版 —— 身份菜单里不再有"即时切换身份"的按钮 */
t('身份菜单不做即时切换（与正式版一致：退出后重新登录）', (() => { api.renderChipPop(); const h = document.getElementById('chipPop').innerHTML; return !/data-role="/.test(h) && (h.match(/data-go="login"/g) || []).length === 2 })())
/* 行级悬停 + 分隔线 + 板块不再整块放大 */
t('表格行悬停高亮到行（含左侧主题色条）', /\.tbl tbody tr:hover td\{background:var\(--primary-soft\)\}/.test(html) && /\.tbl tbody tr:hover td:first-child\{box-shadow:inset 3px 0 0 var\(--accent\)\}/.test(html))
t('列表行悬停高亮到行', /\.list-item:hover\{background:var\(--primary-soft\);box-shadow:inset 3px 0 0 var\(--accent\)\}/.test(html))
t('板块悬停不再整块上浮（只有统计卡保留）', !/\.card:hover\{[^}]*transform:translateY/.test(html) && /\.stat:hover\{[^}]*transform:translateY\(-2px\)/.test(html))
t('行分隔线加粗到 2px 且用 border-strong', /\.tbl td\{[^}]*border-bottom:2px solid var\(--border-strong\)/.test(html) && /\.list-item\{[^}]*border-bottom:2px solid var\(--border-strong\)/.test(html) && /\.card-hd\{[^}]*border-bottom:2px solid var\(--border-strong\)/.test(html))
t('列表行左右留出内边距（不再贴边框）', /\.list-item\{[^}]*padding:14px 16px/.test(html))
/* 版式与主题名 */
t('工作台固定为左文右图（切换器已移除）', /V\.home = function\(\)\{[\s\S]{0,260}<div class="hero">/.test(html) && !html.includes('hv-switch') && !html.includes('data-hv'))
t('首页不再有 B/C 两版样式', !/\.hero\.hv-b/.test(html) && !/\.hero\.hv-c/.test(html))
t('登录页固定时光塔（开关与候选 B 已移除）', /\.login-aside \.bg\{[^}]*var\(--photo-login-a\)/.test(html) && !html.includes('lgb-switch') && !html.includes('--photo-login-b'))
t('主题名统一三字：青瓷绿 / 墨夜黑', api.THEMES.map((x) => x.name).join(',') === '师大蓝,银杏暖,青瓷绿,墨玉青,师大绛,墨夜黑', api.THEMES.map((x) => x.name).join(','))
/* v8 截图自查抓到的真 bug：.noperm 曾是 flex，裸文字 + 行内 span 的兄弟结构会被压成 min-content（文字竖排） */
t('noperm 是流式排版（不再把行内片段压成竖排）', /\.noperm\{display:block/.test(html) && !/\.noperm\{display:flex/.test(html))
/* 只看真实标记，注释里提到旧写法不算（HTML 注释与 JS 块注释都先剥掉） */
const htmlNoComments = html.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '')
t('详情/治理页不再重复写「拟稿人」', !/拟稿：/.test(htmlNoComments) && !/<div class="k">拟稿<\/div>/.test(htmlNoComments))

console.log('=== ⑤d v8.2：编辑接口必填 versionNum → 409 版本冲突 ===')
/* 后端 PUT /api/documents/{id} 新增必填 versionNum，版本不一致返回 409（陈旧表单防覆盖）。
   预览稿必须把这一态渲染出来，并把契约登记进「落地接口对照」，否则前端 M5 按老契约写就会漏掉 409。 */
t('设计系统页有 409 版本冲突提示与 toast', /id="conflictAlert"/.test(api.render('kit')) && /版本冲突（409）/.test(api.render('kit'))
  && /保存失败：内容已被他人修改/.test(api.render('kit')))
t('落地接口对照登记了必填 versionNum 契约', /versionNum<\/span>（必填/.test(api.render('kit'))
  && /PUT \/api\/documents\/\{id\}[\s\S]{0,200}409/.test(api.render('kit')) && /四项变更已落地/.test(api.render('kit')))

console.log('=== ⑤b v6：预览条 / 选择器位置 / 三版式首页 / 退出登录 ===')
/* ①预览条：白底白字的胶囊必须消失，chip 必须有底色与文字色 */
const pvbarCss = (html.split('.pvbar{')[1] || '').split('}')[0]
t('预览条跟随主题（不再写死黑底）', /background:var\(--surface\)/.test(pvbarCss) && !/#0B1114/.test(pvbarCss))
t('预览条胶囊有底色与文字色（白底白字 bug 已修）', /\.pvbar \.pvchip\{[^}]*background:var\(--accent-soft\)/.test(html) && /\.pvbar \.pvchip\{[^}]*color:var\(--accent\)/.test(html))
t('预览条不再有 .tag 白字胶囊', !/\.pvbar \.tag\{/.test(html))
t('旧提示语已删除', !/切身份看菜单与按钮增减/.test(html))
t('预览条写明「只属于预览稿」', /只属于预览稿/.test(html))
/* ②主题选择器位置：只在预览条一份，浮动按钮与工作区右上角那处都取消 */
t('主题选择器在预览条里（常驻，登录页也能换）', /<div class="pvbar">[\s\S]*?id="themeBtn"[\s\S]*?<\/div>\s*<!--/.test(html) || (html.indexOf('id="themeBtn"') > html.indexOf('class="pvbar"') && html.indexOf('id="themeBtn"') < html.indexOf('id="viewLogin"')))
t('主题选择器不再放在工作区右上角', !/class="top-right">[\s\S]{0,200}id="themeBtn"/.test(html))
t('登录页浮动换肤按钮已取消', !html.includes('id="themeFab"') && !html.includes('theme-fab'))
t('选择器仍保留可视化色卡弹层', html.includes('id="themePop"') && html.includes('class="theme-grid"') && /id="themeName"/.test(html))
/* ③直达下拉按权限分组 */
/* 计数不是猜的：教职工可达的 8 个视图 = home/search/detail/edit/mine/me/kit/e403；
   被拦的 6 个 = review/governance/taxonomy/users/roles/org（与 nav 的 5 项菜单不同，detail 与 kit 不占菜单位） */
api.setRole('staff')
const optStaff = api.buildViewOptions()
t('直达下拉：教职工分组计数正确', optStaff.ok === 8 && optStaff.denied === 6, JSON.stringify(optStaff))
api.setRole('admin')
const optAdmin = api.buildViewOptions()
t('直达下拉：系统管理员全部可达', optAdmin.ok === 14 && optAdmin.denied === 0, JSON.stringify(optAdmin))
t('直达下拉用 optgroup 标注有无权限', html.includes('function buildViewOptions') && /无权限（/.test(html) && /有权限（/.test(html))
/* ④工作台横幅：v8 起固定为「左文右图」，切换器与 B/C 两版样式都已移除 */
const homeOut = api.render('home')
t('工作台横幅固定左文右图', /class="hero"/.test(homeOut) && !/hv-/.test(homeOut) && (homeOut.match(/class="lead"/g) || []).length === 1 && (homeOut.match(/class="shot"/g) || []).length === 1)
t('工作台横幅渲染健康', homeOut.length > 800 && divBalanced(stripData(homeOut)) && !/undefined|\[object Object\]|NaN/.test(stripData(homeOut)), homeOut.length + ' 字符')
t('三版式切换器已移除', !html.includes('hv-switch') && !html.includes('data-hv') && !/\.hero\.hv-[bc]/.test(html))
/* ⑤登录页背景：v8 起固定时光塔 */
t('登录页背景固定时光塔（单张、走变量）', /\.login-aside \.bg\{[^}]*background-image:var\(--photo-login-a\)/.test(html) && html.includes('--photo-login-a:url(') && !html.includes('--photo-login-b') && !html.includes('lgb-switch'))
t('登录页遮罩跟随主题且更轻', /\.login-aside \.veil\{[^}]*color-mix\(in srgb,var\(--sidebar-bg\) 92%/.test(html) && !/\.login-aside \.veil\{[^}]*rgba\(11,24,54/.test(html) && /\.login-aside \.inner,\.login-aside \.inner \*,\.login-aside \.foot\{text-shadow/.test(html))
t('青瓷绿 = 华砚湖畔', /青瓷绿 · 华砚湖畔/.test(api.render('kit')))
/* ⑤退出登录 */
t('侧栏底部常驻退出登录', /class="side-logout" data-go="login"/.test(html) && /\.side-logout\{/.test(html))
t('退出登录会回到登录页', /data-go="login"/.test(html) && /document\.getElementById\('viewLogin'\)/.test(html))
t('登录按钮点完进工作台', /getElementById\('lgBtn'\)[\s\S]{0,160}go\('home'\)/.test(html))
/* ⑥看得更清楚：2px 描边 + 悬停抬起 + 按钮悬停变色 */
t('卡片描边加粗到 2px', /\.card\{[\s\S]{0,160}border:2px solid var\(--border\)/.test(html))
t('板块悬停只换描边（行级反馈交给行）', /\.card:hover\{[^}]*color-mix\(in srgb,var\(--accent\) 46%/.test(html) && !/\.card:hover\{[^}]*transform/.test(html))
t('按钮悬停变色', /\.btn:hover\{[\s\S]{0,160}background:var\(--primary-soft\)/.test(html))
t('链接悬停变色', /\.link:hover\{[^}]*color:var\(--accent\)/.test(html))
t('横幅也是 2px 描边并参与悬停', /\.hero\{[^}]*border:2px solid var\(--border\)/.test(html) && /\.hero:hover\{/.test(html))
/* ⑦换图 */
t('师大蓝换成时光塔高清版（石碑已弃用）', /师大蓝 · 时光塔/.test(api.render('kit')) && !/校训石碑/.test(api.render('kit')))
t('青瓷绿 = 华砚湖畔（用户改回）', /青瓷绿 · 华砚湖畔/.test(api.render('kit')) && !/玉兰与蓝天/.test(api.render('kit')))
t('墨玉青换成天下石牌坊（不再与师大蓝重复时光塔）', /墨玉青 · 天下石牌坊/.test(api.render('kit')) && !/墨玉青 · 时光塔下/.test(api.render('kit')))
t('登录页背景已固定为时光塔（无候选开关）', !html.includes('lgb-switch') && !html.includes('--photo-login-b'))
t('画廊说明写明不放大', /裁剪宽度 ≥ 输出宽度/.test(api.render('kit')))
t('设计系统页列出后端差异清单', /演示数据已按校园口径重新种子化/.test(api.render('kit')) && /分类体系：设计 10 类 41 子类/.test(api.render('kit')))
t('预览数据与新版种子同口径', /驻县教师职责/.test(api.render('detail')) && api.DATA.users.every((u) => !/技术部|产品部|教务处/.test(u.dept)))

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
t('主题选择器在常驻预览条里（登录页也能换肤）', html.indexOf('id="themeBtn"') > html.indexOf('class="pvbar"') && html.indexOf('id="themeBtn"') < html.indexOf('id="viewLogin"'))
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

console.log('=== ⑦ 视觉资源（校徽 / 题字 / 六张主题照片） ===')
t('校徽已内联（data URI）', /<img class="brand-logo" src="data:image\/png;base64,/.test(html))
t('校训题字已内联', html.includes('alt="校训：怀天下 求真知"'))
t('favicon 用校徽', /<link rel="icon" href="data:image\/png;base64,/.test(html))
t('六套主题各有独立横幅照片', ['hebtu', 'gingko', 'celadon', 'ink', 'jiang', 'night'].every((id) => html.includes(`--photo-${id}:url(`) && html.includes(`html[data-theme="${id}"] .hero .shot{background-image:var(--photo-${id})}`)))
const jpegCount = (html.match(/data:image\/jpeg;base64,/g) || []).length
t('共 8 张 JPEG 且每张只内联一次（不重复膨胀）', jpegCount === 8, '出现 ' + jpegCount + ' 次 = 6 张主题横幅 + 登录页固定时光塔 + 画廊小图')
t('横幅是「左渐变 + 右照片」两栏，不是整张铺底', html.includes('<div class="lead">') && html.includes('<div class="shot">') && !/class="photo"/.test(html))
t('登录页背景走变量（1:1 竖裁 1200x1200，不再被 cover 拉大）', /\.login-aside \.bg\{[^}]*background-image:var\(--photo-login-a\)/.test(html) && !html.includes('__ASSET_CAMPUS__'))
t('没有残留 __ASSET_ 占位符', !html.includes('__ASSET_'))
t('没有「师」字假 Logo', !html.includes('<div class="brand-mark">师</div>'))

console.log('=== ⑦b 侧边栏对比度（亮堂 ≠ 全白侧栏） ===')
const sidebarBgs = api.THEMES.map((x) => {
  const re = new RegExp('html\\[data-theme="' + x.id + '"]\\{([^}]*)\\}', 'g')
  let m, found = null
  while ((m = re.exec(html))) { const b = m[1].match(/--sidebar-bg:\s*(#[0-9A-Fa-f]{6})/); if (b) found = b[1] }
  return { id: x.id, bg: found }
})
const lum = (hex) => {
  const c = [1, 3, 5].map((i) => parseInt(hex.substr(i, 2), 16) / 255).map((v) => (v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4)))
  return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
}
for (const s of sidebarBgs) {
  t(`侧栏 ${s.id} 是主题色而非白色`, !!s.bg && lum(s.bg) < 0.35, `--sidebar-bg=${s.bg} 亮度=${s.bg ? lum(s.bg).toFixed(3) : 'N/A'}`)
}
t('六套侧栏颜色互不相同', new Set(sidebarBgs.map((s) => s.bg)).size === 6)

console.log('=== ⑧ 标记卫生 ===')
t('正文无内联 style 属性', !/\sstyle\s*=/.test(html))
t('无 style-xxx 占位属性', !/\sstyle-[a-z]/.test(html))
t('无外部资源引用', !/<script[^>]*src/i.test(html) && !/<link[^>]*href="https?:/i.test(html) && (html.match(/<img[^>]*src="(?!data:)/g) || []).length === 0)

console.log(`\nTOTAL pass=${pass} fail=${fail}`)
process.exit(fail === 0 ? 0 : 1)
