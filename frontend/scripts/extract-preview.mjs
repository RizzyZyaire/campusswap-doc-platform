// =============================================================================
//  从 UI-PREVIEW.html 提取「主题令牌 + 组件层 CSS + 图片素材」，生成正式前端的静态资源。
//
//  为什么这么做：预览稿 v8.2 是用户逐张确认过的视觉定稿，令牌、组件样式、素材全都内联在那一个
//  文件里。正式工程按 UI_UX_SPECIFICATION §2/§3 必须变成「Tailwind 原子类 + main.css 的
//  @layer components + src/assets 静态文件」。这个脚本负责把三者还原成可评审、可 diff 的形态，
//  避免手抄 732 行 CSS 出错；预览稿改了视觉就重跑它。
//
//  用法：pnpm run sync-preview      （幂等，可反复重跑）
//  产物：
//    src/styles/theme.css        6 套主题令牌（与预览稿逐字一致）
//    src/styles/components.css   组件层 CSS（@layer components，来自预览稿，剔除预览稿专用选择器）
//    src/assets/banners/*.jpg    6 张主题横幅
//    src/assets/login/tower.jpg  登录页背景（1:1 竖裁 1200×1200）
//    src/assets/brand/*          校徽 logo / 站点图标 / 校训题字 / 画廊小图
//    src/assets/manifest.json    素材清单（尺寸与字节数由 pnpm run sync-preview 打印）
//
//  命名说明：预览稿里每个主题各有一个 --photo-<theme>；正式前端统一成 --hero-photo（每套主题
//  赋不同图片），登录页统一成 --login-photo，组件里只写一个变量名。
// =============================================================================
import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const previewPath = join(root, '..', 'docs', '02-design', 'UI-PREVIEW.html')
const srcDir = join(root, 'src')
const assetsDir = join(srcDir, 'assets')
const stylesDir = join(srcDir, 'styles')
const THEMES = ['hebtu', 'gingko', 'celadon', 'ink', 'jiang', 'night']

if (!existsSync(previewPath)) {
  console.error('找不到预览稿：' + previewPath)
  process.exit(1)
}
const html = readFileSync(previewPath, 'utf8')
const manifest = []
const sha = (buf) => createHash('sha256').update(buf).digest('hex')
mkdirSync(assetsDir, { recursive: true })
mkdirSync(stylesDir, { recursive: true })

function writeAsset(relPath, base64, kind) {
  const buf = Buffer.from(base64, 'base64')
  const full = join(assetsDir, relPath)
  mkdirSync(dirname(full), { recursive: true })
  writeFileSync(full, buf)
  bySha.set(sha(buf), 'assets/' + relPath.replace(/\\/g, '/'))
  manifest.push({ path: 'assets/' + relPath.replace(/\\/g, '/'), kind, bytes: buf.length, sha256: sha(buf).slice(0, 16) })
  return buf.length
}
/** 已落盘素材的 sha → 相对路径（组件层里的内联背景图若是同一张，直接复用，不重复占体积）。 */
const bySha = new Map()

/* ---------- 1) 主题令牌与图片（都藏在 CSS 变量的 data URI 里） ---------- */
function readVarBlock(block) {
  const photos = {}
  const photoRe = /--photo-([a-z-]+)\s*:\s*url\("data:image\/jpeg;base64,([^"]+)"\)/g
  let pm
  while ((pm = photoRe.exec(block))) photos[pm[1]] = pm[2]
  const rest = block.replace(/--photo-[a-z-]+\s*:\s*url\("data:[^"]*"\)\s*;?/g, '')
  const decls = []
  for (const raw of rest.split(';')) {
    const d = raw.trim()
    if (d.startsWith('--') && d.includes(':')) decls.push(d)
  }
  return { photos, decls }
}

const rootBlock = /:root\{([\s\S]*?)\}/.exec(html)
if (!rootBlock) throw new Error('预览稿里找不到 :root 令牌块')
const rootTokens = readVarBlock(rootBlock[1])
const baseDecls = rootTokens.decls.filter((d) => !d.startsWith('--photo-'))
const photosAll = { ...rootTokens.photos } // 6 张横幅与登录页背景都放在 :root 里
const tokensByTheme = {}

const themeRe = /html\[data-theme="([a-z]+)"\]\{([\s\S]*?)\}/g
let tm
while ((tm = themeRe.exec(html))) {
  const id = tm[1]
  if (!THEMES.includes(id)) continue
  const { photos, decls } = readVarBlock(tm[2])
  const slot = (tokensByTheme[id] ||= new Map())
  Object.assign(photosAll, photos)
  for (const d of decls) slot.set(d.slice(0, d.indexOf(':')), d)
}

if (!photosAll['login-a']) throw new Error('找不到 --photo-login-a')
writeAsset(join('login', 'tower.jpg'), photosAll['login-a'], 'login-background')
for (const theme of THEMES) {
  if (!photosAll[theme]) throw new Error(`素材里缺少 --photo-${theme}`)
  writeAsset(join('banners', `${theme}.jpg`), photosAll[theme], 'hero-banner')
}

/* ---------- 2) 站点图标与正文图片（校徽在预览稿里被内联了 4 次，按语义名去重） ---------- */
const favicon = /<link rel="icon" href="data:image\/(png|jpeg);base64,([^"]+)"/.exec(html)
if (favicon) writeAsset(join('brand', `favicon.${favicon[1]}`), favicon[2], 'favicon')

const NAME_BY_CLASS = { crest: 'logo', 'brand-logo': 'logo', 'motto-img': 'motto', 'asset wide': 'gallery', asset: 'gallery' }
const writtenNames = new Set()
const tagRe = /<img([^>]*?)src="data:image\/(png|jpeg);base64,([^"]+)"([^>]*?)>/g
let im
while ((im = tagRe.exec(html))) {
  const attrs = (im[1] || '') + ' ' + (im[4] || '')
  const cls = ((/class="([^"]*)"/.exec(attrs) || [, ''])[1] || '').trim()
  const name = NAME_BY_CLASS[cls] || NAME_BY_CLASS[cls.split(/\s+/)[0]] || null
  if (!name || writtenNames.has(name)) continue
  writtenNames.add(name)
  writeAsset(join('brand', `${name}.${im[2]}`), im[3], 'brand')
}

/* ---------- 3) theme.css ---------- */
const tokenLines = []
tokenLines.push('/* =============================================================================')
tokenLines.push('   主题令牌 —— 由 frontend/scripts/extract-preview.mjs 从 docs/02-design/UI-PREVIEW.html 提取。')
tokenLines.push('   请勿手改：要改视觉先改预览稿（用户逐张确认过的定稿），再跑 pnpm run sync-preview。')
tokenLines.push('   与预览稿的唯一命名差异：每套主题的 --photo-<theme> → --hero-photo，--photo-login-a → --login-photo。')
tokenLines.push('   ============================================================================= */')
tokenLines.push('')
tokenLines.push(':root{')
for (const d of baseDecls) tokenLines.push('  ' + d + ';')
tokenLines.push(`  --login-photo:url('../assets/login/tower.jpg');`)
tokenLines.push('}')
tokenLines.push('')
for (const theme of THEMES) {
  tokenLines.push(`html[data-theme="${theme}"]{`)
  for (const d of tokensByTheme[theme].values()) tokenLines.push('  ' + d + ';')
  tokenLines.push(`  --hero-photo:url('../assets/banners/${theme}.jpg');`)
  tokenLines.push('}')
  tokenLines.push('')
}
writeFileSync(join(stylesDir, 'theme.css'), tokenLines.join('\n'), 'utf8')
manifest.push({ path: 'styles/theme.css', kind: 'tokens', bytes: Buffer.byteLength(tokenLines.join('\n')), sha256: '—' })

/* ---------- 4) components.css（组件层，来自预览稿） ---------- */
// ⚠️ 必须先摘掉 HTML 注释：预览稿开头的说明注释里**写了字面量 `<style>`**（"所有样式在下方 <style>…"），
// 直接 /<style>([\s\S]*?)<\/style>/ 会从注释里那一处起算，把注释尾巴与一小段 HTML 一起吞进来 ——
// 产物的第一条"规则"会是一段非法选择器（真实缺陷，2026-09-23 修复）。
const htmlNoComments = html.replace(/<!--[\s\S]*?-->/g, '')
const styleMatch = /<style>([\s\S]*?)<\/style>/.exec(htmlNoComments)
if (!styleMatch) throw new Error('预览稿里找不到 <style> 块')
const rawCss = styleMatch[1]
{
  const head = rawCss.replace(/\/\*[\s\S]*?\*\//g, ' ').trim().slice(0, 40)
  if (rawCss.includes('-->') || !/^[.:@#*[a-zA-Z]/.test(head)) {
    throw new Error('取到的样式块开头不像 CSS（可能又匹配到了注释里的 <style>）：' + JSON.stringify(head))
  }
}

// 4a-0) 先把带**嵌套花括号**的 at-rule 整块摘出来（`@keyframes` 是预览稿里唯一一条）。
//       为什么必须单独处理：下面的平铺正则 `[^{}]+\{[^{}]*\}` 不允许花括号嵌套，遇到
//       `@keyframes sk{0%,100%{opacity:1}50%{opacity:.5}}` 会错位 —— 头部 `@keyframes sk` 静默消失，
//       产物里只剩 `0%,100%{opacity:1}` 与 `50%{opacity:.5}` 两行孤立声明（非法 CSS，动画也失效）。
//       这是早期版本的真实缺陷，2026-09-23 修复。
const atRules = []
let flatCss = ''
{
  // 注释里也会出现 `@layer components` 这类字样（预览稿的说明性注释），直接扫原文会把注释当成 at-rule
  // 起点、一路吞掉后面一大段 CSS。做法：把注释放同等长度的空格「遮罩」后扫描，再按同一索引从原文切片，
  // 这样既不会误判，注释也原样保留在产物里（它们解释了每处 v8.x 修的是什么）。
  const masked = rawCss.replace(/\/\*[\s\S]*?\*\//g, (c) => ' '.repeat(c.length))
  const atRe = /@[a-zA-Z-]+[^{;]*\{/g
  let m
  let last = 0
  while ((m = atRe.exec(masked))) {
    let depth = 1
    let i = atRe.lastIndex
    while (i < masked.length && depth > 0) {
      const ch = masked[i]
      if (ch === '{') depth++
      else if (ch === '}') depth--
      i++
    }
    if (depth !== 0) throw new Error('at-rule 花括号不配对：' + m[0].trim())
    flatCss += rawCss.slice(last, m.index)
    atRules.push(rawCss.slice(m.index, i).replace(/\s+/g, ' ').trim())
    last = i
    atRe.lastIndex = i
  }
  flatCss += rawCss.slice(last)
}

// 4a) 逐条规则解析（去掉 at-rule 后，剩下的就是平铺的「选择器{声明}」序列），
//     比用正则切块稳：不会再出现括号不配对或把 var(...) 从中间截断的问题。
const PREVIEW_ONLY = /(^|[\s,>])(\.pvbar|\.themepick|\.theme-btn|\.dots|\.theme-pop|\.theme-grid|\.tp)(?![a-zA-Z0-9_-])/
const rules = []
/** 样式块末尾的注释（不是规则，平铺正则吃不到）：原样保留在产物尾部，避免"注释凭空消失"。 */
let trailingNotes = ''
{
  const re = /([^{}]+)\{([^{}]*)\}/g
  let m
  let end = 0
  while ((m = re.exec(flatCss))) {
    // ⚠️ 必须在循环内记录 re.lastIndex：`exec` 返回 null 时会把 lastIndex 归零，
    //    循环外用它会得到"整段都是未解析内容"的假象（早期版本就被这个坑误导过）。
    end = re.lastIndex
    const raw = m[1].replace(/\s+/g, ' ').trim()
    // 选择器前面常带注释（预览稿用它们记录每次修订改了什么、为什么改）。
    // 注释要**先摘出来**再判断选择器：否则 `/* … */ html[data-theme="ink"]` 既不等于 ':root'、
    // 也不以 'html[data-theme=' 开头，令牌块就漏进了组件层。
    const notes = (raw.match(/\/\*[\s\S]*?\*\//g) || []).join(' ')
    const sel = raw.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/\s+/g, ' ').trim()
    if (!sel) continue
    // 兜底：平铺区里不该再出现 at-rule 或孤立声明（前者说明 4a-0 漏了，后者说明又错位了）
    if (sel.startsWith('@')) throw new Error('平铺区出现未处理的 at-rule：' + sel)
    if (/^[0-9]+%|^(from|to)$/.test(sel)) throw new Error('出现孤立的 keyframes 帧选择器：' + sel)
    rules.push({ sel, notes, body: m[2].trim() })
  }
  // 正则吃剩下的只允许是「注释 + 空白 + 收尾括号」；去掉注释后若还有别的字符，说明解析真的错位了。
  const leftover = flatCss.slice(end)
  const leftoverBare = leftover.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/[)}]/g, ' ').trim()
  if (leftoverBare) {
    throw new Error('样式块末尾有未解析内容：' + leftoverBare.slice(0, 60))
  }
  trailingNotes = (leftover.match(/\/\*[\s\S]*?\*\//g) || []).join(' ').replace(/\s+/g, ' ').trim()
}

const kept = []
let droppedPreview = 0
let droppedTokens = 0
let renamedVars = 0
for (const rule of rules) {
  const sel = rule.sel
  if (sel === ':root' || sel.startsWith('html[data-theme=')) {
    droppedTokens++ // 令牌块与「按主题换图」的规则交给 theme.css（--hero-photo / --login-photo）
    continue
  }
  if (PREVIEW_ONLY.test(sel)) {
    droppedPreview++ // 预览稿专用控件（预览条 / 主题选择器）
    continue
  }
  // 图片变量改名后**保留**整条规则 —— 早期版本把含 var(--photo-*) 的规则整条丢掉，
  // 结果 .login-aside .bg 的 position/inset/background-size 一起没了（登录页塔图不显示）。
  const renamed = rule.body
    .replace(/var\(--photo-login-a\)/g, 'var(--login-photo)')
    .replace(/var\(--photo-[a-z-]+\)/g, 'var(--hero-photo)')
  if (renamed !== rule.body) renamedVars++
  kept.push({ sel, notes: rule.notes, body: renamed })
}

// 4b) 组件层里还内联着图片（background-image:url(data:...)）：抽成静态文件，URL 改成相对路径，
//     否则组件层会有 1.5 MB 的 base64（既不可 diff，也不该进源码）。
let bgIndex = 0
const rulesWithAssets = kept.map((rule) => {
  if (!rule.body.includes('data:image/')) return rule
  const slug = rule.sel.replace(/[^a-zA-Z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 32) || 'bg'
  const body = rule.body.replace(/url\("data:image\/(png|jpeg);base64,([^"]+)"\)/g, (_all, ext, b64) => {
    const buf = Buffer.from(b64, 'base64')
    const known = bySha.get(sha(buf))
    if (known) return `url('../${known}')` // 与已抽出的横幅/登录图同源，直接复用
    const rel = join('bg', `${slug}-${++bgIndex}.${ext}`)
    writeAsset(rel, b64, 'component-background')
    return `url('../assets/${rel.replace(/\\/g, '/')}')`
  })
  return { sel: rule.sel, notes: rule.notes, body }
})

// 4c) 图片变量改名：预览稿每套主题各写一条 html[data-theme=X] .hero .shot{background-image:var(--photo-X)}，
//     正式工程里 --hero-photo 已由 theme.css 按主题给出，所以补一条通用规则即可。
//     注释单独占一行放回选择器上方：产物仍是可读的（每次 v8.x 修订的原因都留在原地）。
const css = rulesWithAssets.map((r) => (r.notes ? r.notes + '\n' : '') + `${r.sel}{${r.body}}`).join('\n') + (atRules.length ? '\n' + atRules.join('\n') : '')
console.log(`  组件层：保留 ${rulesWithAssets.length} 条规则（其中 ${renamedVars} 条改了图片变量名），at-rule ${atRules.length} 条，丢弃预览稿专用 ${droppedPreview} 条、令牌块 ${droppedTokens} 条，抽出内联背景图 ${bgIndex} 张`)

const compLines = []
compLines.push('/* =============================================================================')
compLines.push('   组件层 —— 由 frontend/scripts/extract-preview.mjs 从 docs/02-design/UI-PREVIEW.html 逐条规则提取。')
compLines.push('   请勿手改（要改先改预览稿再跑 pnpm run sync-preview）。预览稿专用选择器与令牌块已剔除；')
compLines.push('   图片变量改名：--photo-<theme> → --hero-photo、--photo-login-a → --login-photo（由 theme.css 提供）。')
compLines.push('   ============================================================================= */')
compLines.push('@layer components {')
for (const line of css.split('\n')) compLines.push('  ' + line)
if (trailingNotes) compLines.push('  ' + trailingNotes)
compLines.push('  .hero .shot{background-image:var(--hero-photo)}')
compLines.push('  .login-aside .bg{background-image:var(--login-photo)}')
compLines.push('}')
writeFileSync(join(stylesDir, 'components.css'), compLines.join('\n'), 'utf8')
manifest.push({ path: 'styles/components.css', kind: 'components', bytes: Buffer.byteLength(compLines.join('\n')), sha256: sha(Buffer.from(compLines.join('\n'))).slice(0, 16) })

/* ---------- 5) manifest ---------- */
manifest.sort((a, b) => a.path.localeCompare(b.path))
writeFileSync(join(assetsDir, 'manifest.json'), JSON.stringify({
  generatedFrom: 'docs/02-design/UI-PREVIEW.html',
  note: '由 frontend/scripts/extract-preview.mjs 生成；预览稿换了素材/样式就重跑 pnpm run sync-preview。',
  count: manifest.length,
  files: manifest
}, null, 2) + '\n', 'utf8')

console.log('== 预览稿同步完成（令牌 + 组件层 + 素材）==')
for (const m of manifest) console.log(`${m.path.padEnd(30)} ${String(m.bytes).padStart(8)} B  ${m.sha256}`)
console.log(`\n主题 ${THEMES.length} 套；组件层 ${css.split('\n').length} 行；合计 ${manifest.length} 项`)
