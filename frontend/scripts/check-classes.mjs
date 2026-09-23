// =============================================================================
//  类名体检：模板里用到的每个 class 是否真的存在于产物 CSS 里。
//
//  为什么需要它：设计系统的类名（.card / .badge.plain / .w-fix2 / .list-item.on …）有 360 多条，
//  模板里写错一个字母不会报错 —— 页面只是"少了一块样式"，靠肉眼很难发现（尤其 `.ml8` 这种
//  以为有、其实没有的 Tailwind 风格类名）。而 `vite build` 后的 CSS 是**唯一权威**：
//  Tailwind 会扫源码、components.css 会整段进产物，所以"用到的类一定在产物 CSS 里"是硬约束。
//
//  用法：pnpm run build 之后跑  node scripts/check-classes.mjs
//  产物：无（只打印结论；有未知类名时 exit 1，便于挂进收口自检）
// =============================================================================
import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { dirname, join, extname } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..')
const srcDir = join(root, 'src')
const distAssets = join(root, 'dist', 'assets')

if (!existsSync(distAssets)) {
  console.error('找不到 dist/assets —— 先跑 pnpm run build 再体检')
  process.exit(1)
}

/* ---------- 1) 产物 CSS 里出现过的类名 ---------- */
const cssFiles = readdirSync(distAssets).filter((f) => extname(f) === '.css')
if (!cssFiles.length) {
  console.error('dist/assets 里没有 CSS')
  process.exit(1)
}
const defined = new Set()
for (const f of cssFiles) {
  const css = readFileSync(join(distAssets, f), 'utf8')
  // 只取选择器里 .foo 形式的类名，并反转义（Tailwind 会把 . / : / [ ] 等写成 \. \: …）
  for (const m of css.matchAll(/\.((?:\\.|[A-Za-z0-9_-])+)/g)) {
    defined.add(m[1].replace(/\\(.)/g, '$1'))
  }
}

/* ---------- 2) 模板里用到的类名 ---------- */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, out)
    else if (extname(full) === '.vue') out.push(full)
  }
  return out
}

/** 从一个 class 字符串里拆出类名 token。 */
function tokens(text) {
  return text
    .split(/[\s]+/)
    .map((t) => t.trim())
    .filter((t) => t && !t.includes('{') && !t.includes('(') && !t.includes('$') && !t.startsWith('.'))
}

const used = new Map() // 类名 → 首次出现的 文件:行
const problems = []
for (const file of walk(srcDir)) {
  const text = readFileSync(file, 'utf8')
  const lines = text.split(/\r?\n/)
  const rel = file.replace(root + '\\', '').replace(/\\/g, '/')
  lines.forEach((line, i) => {
    const at = `${rel}:${i + 1}`
    // a) 静态 class="..." / class='...'
    for (const m of line.matchAll(/\sclass="([^"]*)"/g)) {
      for (const t of tokens(m[1])) if (!used.has(t)) used.set(t, at)
    }
    // b) :class 绑定：只取"真的是类名"的字符串 —— 对象键，以及不在比较/三元/拼接里的字面量。
    //    反面例子（曾被误报）：{ on: mode === 'edit' } 里的 'edit'、'lv' + depth 拼出来的 'lv'。
    for (const m of line.matchAll(/:class="([^"]*)"/g)) {
      const expr = m[1]
      for (const key of expr.matchAll(/([A-Za-z][A-Za-z0-9_-]*)\s*:/g)) {
        const t = key[1]
        if (!used.has(t)) used.set(t, at)
      }
      for (const lit of expr.matchAll(/'([^']*)'|"([^"]*)"/g)) {
        const value = lit[1] ?? lit[2] ?? ''
        const start = lit.index ?? 0
        const before = expr.slice(Math.max(0, start - 3), start)
        const after = expr.slice(start + lit[0].length, start + lit[0].length + 3)
        if (/[=!?]/.test(before) || /[=!?]/.test(after)) continue // 比较 / 三元里的字面量
        if (/\+/.test(before) || /\+/.test(after)) continue // 'lv' + depth 这类拼接
        for (const t of tokens(value)) if (!used.has(t)) used.set(t, at)
      }
    }
  })
}

/* ---------- 3) 对账 ---------- */
// 少数类名由运行时拼接（StatusBadge 的 `badge ${status}`），它们不会以静态字面量出现，
// 这里按"产物里已定义"处理，不需要白名单。
for (const [cls, at] of [...used.entries()].sort()) {
  if (defined.has(cls)) continue
  problems.push({ cls, at })
}

console.log(`产物 CSS：${cssFiles.join(', ')}，定义类名 ${defined.size} 个`)
console.log(`模板用到类名 ${used.size} 个`)
if (!problems.length) {
  console.log('OK：模板里的每个 class 都能在产物 CSS 里找到')
  process.exit(0)
}
console.error(`\n以下 ${problems.length} 个类名在产物 CSS 里不存在（写错了？还是样式没进构建？）：`)
for (const p of problems) console.error(`  ${p.cls}   ${p.at}`)
process.exit(1)
