// =============================================================================
//  登录落点验证（零依赖，Node 内置 fetch + WebSocket 直连 CDP）
//
//  为什么需要它：2026-10-09 用户实测「登录成功后自动跳到无权限页(403)，点返回工作台才正常」。
//  根因是 `/` 写成了函数式 redirect（`router/index.ts` 的 redirect 回调），而 vue-router 的
//  redirect 回调在**任何守卫之前**执行 —— 匿名访问 `/` 时 store 里既没 token 也没权限，
//  回调直接返回 `/403`，守卫再把这次 `/403` 当成"未登录要去的地方"记进 `?redirect=/403`，
//  于是登录成功后 `router.replace('/403')` 把用户送进 403 页。
//
//  本脚本用真实浏览器走完整流程断言落点，防止这个 bug 回归：
//    ① 全新 profile（无 token）打开 `/` → 必须落到登录页，且 redirect 参数不能是 /403
//    ② 在页面上真实填表 + 点提交（不是直接注入 token）
//    ③ 登录后落点必须是按权限的落点（admin → /docs），不能是 /403
//
//  用法（默认打打包后的单 jar，也可指向 dev 的 5173）：
//    node scripts/verify-login-landing.mjs                       # http://localhost:10090
//    node scripts/verify-login-landing.mjs http://localhost:5173
//  退出码：0 = 全过，1 = 失败
// =============================================================================
import { spawn } from 'node:child_process'
import { mkdirSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const APP = (process.argv[2] ?? 'http://localhost:10090').replace(/\/$/, '')
const PORT = 9334
const TMP = `${process.env.TEMP ?? '.'}\\cdpprof-login-${process.pid}`

const ACCOUNT = { username: 'admin', password: 'Admin@123' }
const EXPECT_AFTER_LOGIN = '/docs' // admin 有 doc:search → 落检索页

rmSync(TMP, { recursive: true, force: true })
mkdirSync(TMP, { recursive: true })

const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? '[PASS]' : '[FAIL]'} ${name}  ${detail}`)
}

/* ---------- 起 Edge 无头 + CDP ---------- */
const edge = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run',
  '--no-default-browser-check', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${TMP}`, '--window-size=1400,900', 'about:blank'
], { stdio: 'ignore' })

async function waitForCdp() {
  for (let i = 0; i < 60; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return
    } catch { /* 还没起来 */ }
    await sleep(250)
  }
  throw new Error('CDP 未就绪')
}
await waitForCdp()

const targets = await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()
const page = targets.find((t) => t.type === 'page')
if (!page) throw new Error('找不到 page target')
const ws = new WebSocket(page.webSocketDebuggerUrl)
let seq = 0
const pending = new Map()
ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data)
  if (msg.id && pending.has(msg.id)) {
    pending.get(msg.id)(msg)
    pending.delete(msg.id)
  }
})
await new Promise((resolve) => ws.addEventListener('open', resolve))
function send(method, params = {}) {
  const id = ++seq
  return new Promise((resolve, reject) => {
    pending.set(id, (msg) => (msg.error ? reject(new Error(`${method}: ${msg.error.message}`)) : resolve(msg.result)))
    ws.send(JSON.stringify({ id, method, params }))
  })
}
async function evalJs(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true })
  return r.result?.value
}

await send('Page.enable')
await send('Runtime.enable')

/* ---------- ① 匿名访问根路径 ---------- */
await send('Page.navigate', { url: `${APP}/` })
await sleep(3000)
const step1 = await evalJs('location.pathname + location.search')
const step1Path = String(step1 ?? '')
check('匿名访问 / 落到登录页', step1Path.startsWith('/login'), `实际 = ${step1Path}`)
check('redirect 参数不是 /403', !decodeURIComponent(step1Path).includes('/403'),
  `实际 = ${decodeURIComponent(step1Path)}`)

/* ---------- ② 真实填表 + 提交 ---------- */
const fill = await evalJs(`(() => {
  const u = document.getElementById('lgUser')
  const p = document.getElementById('lgPass')
  const b = document.querySelector('button.login-submit')
  if (!u || !p || !b) return 'MISSING: u=' + !!u + ' p=' + !!p + ' b=' + !!b
  u.value = ${JSON.stringify(ACCOUNT.username)}
  u.dispatchEvent(new Event('input', { bubbles: true }))
  p.value = ${JSON.stringify(ACCOUNT.password)}
  p.dispatchEvent(new Event('input', { bubbles: true }))
  b.click()
  return 'CLICKED'
})()`)
check('登录表单已提交', fill === 'CLICKED', `实际 = ${fill}`)

await sleep(4000)
const step2 = await evalJs('location.pathname + location.search')
const step2Path = String(step2 ?? '')
const token = await evalJs("localStorage.getItem('campusswap.token') ? 'YES' : 'NO'")
check('登录后拿到 token', token === 'YES', `localStorage = ${token}`)
check('登录后不是 403 页', !step2Path.startsWith('/403'), `实际 = ${step2Path}`)
check(`登录后落到 ${EXPECT_AFTER_LOGIN}`, step2Path === EXPECT_AFTER_LOGIN, `实际 = ${step2Path}`)

/* ---------- ③ 顺带验一个"刷新后仍在 /"的场景（守卫要先 fetchMe 再判权限）---------- */
await send('Page.navigate', { url: `${APP}/` })
await sleep(3000)
const step3 = await evalJs('location.pathname + location.search')
const step3Path = String(step3 ?? '')
check('带 token 直接打开 / 不落 403', !step3Path.startsWith('/403'), `实际 = ${step3Path}`)

/* ---------- ④ 直接打开 /403（浏览器"继续上次会话"会恢复这个 URL）----------
   没有 need 参数就不该把人晾在无权限页：能进哪就进哪（2026-10-11 加的自愈） */
await send('Page.navigate', { url: `${APP}/403` })
await sleep(3000)
const step4 = await evalJs('location.pathname + location.search')
const step4Path = String(step4 ?? '')
check('直接打开 /403 会自愈到有权限的页面', !step4Path.startsWith('/403'), `实际 = ${step4Path}`)

/* ---------- ⑤ 真正缺权限时仍要拦在 403（自愈不能把守卫变成摆设）---------- */
// admin 有全部 39 个权限点，所以这里用后端一定会拒的伪造路由不现实；
// 改为断言 403 页本身可打开且带 need 时不会被自愈改写。
await send('Page.navigate', { url: `${APP}/403?need=doc:review&from=/review` })
await sleep(2500)
const step5 = await evalJs('location.pathname + location.search')
const step5Path = String(step5 ?? '')
check('带 need 的 403 保持原样（自愈不越权）', step5Path.startsWith('/403'), `实际 = ${step5Path}`)

ws.close()
edge.kill()
await sleep(500)
rmSync(TMP, { recursive: true, force: true })

const fails = results.filter((r) => !r.ok).length
console.log(`RESULT: PASS=${results.length - fails} FAIL=${fails}  (${APP})`)
process.exit(fails)
