// =============================================================================
//  带登录态的无头截图工具（零依赖，用 Node 内置 fetch + WebSocket 直连 CDP）。
//
//  为什么需要它：受保护页面必须先有 token 才能渲染，而 `msedge --screenshot` 无法注入 localStorage。
//  做法：先用后端登录接口拿一个真 token → 起 Edge 无头 + remote-debugging → CDP 注入
//  localStorage.token → 导航到目标路由 → 等页面把数据拉回来 → 截图。
//
//  用法：
//    node scripts/shot.mjs <名字> <路由> [宽] [高] [身份]
//    node scripts/shot.mjs workbench /workbench
//    node scripts/shot.mjs search    /docs?keyword=实验室 1600 1200 docadmin
//  产物：D:\DevEnv\logs\shots\m5-<名字>.png
// =============================================================================
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const APP = 'http://localhost:5173'
const API = 'http://localhost:10087/api'
const SHOT_DIR = 'D:\\DevEnv\\logs\\shots'
const PORT = 9333

const ACCOUNTS = {
  admin: { username: 'admin', password: 'Admin@123' },
  docadmin: { username: 'docadmin', password: 'Doc@123456' },
  staff: { username: 'staff', password: 'Staff@123' }
}

const [name = 'page', route = '/workbench', width = '1600', height = '1000', who = 'admin'] = process.argv.slice(2)
const account = ACCOUNTS[who]
if (!account) {
  console.error(`未知身份 ${who}，可选：${Object.keys(ACCOUNTS).join(' / ')}`)
  process.exit(1)
}

mkdirSync(SHOT_DIR, { recursive: true })
const profileDir = `${SHOT_DIR}\\cdpprof-${process.pid}`
rmSync(profileDir, { recursive: true, force: true })

/* ---------- 1) 拿真 token ---------- */
const loginRes = await fetch(`${API}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
  body: JSON.stringify(account)
})
const loginBody = await loginRes.json()
if (loginBody.code !== 200) {
  console.error('登录失败：', loginBody.message)
  process.exit(1)
}
const token = loginBody.data.token
console.log(`已登录 ${who}（${loginBody.data.userInfo.realName}），token 长度 ${token.length}`)

/* ---------- 2) 起 Edge 无头 + CDP ---------- */
const edge = spawn(EDGE, [
  '--headless=new',
  '--disable-gpu',
  '--hide-scrollbars',
  '--no-first-run',
  '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profileDir}`,
  `--window-size=${width},${height}`,
  'about:blank'
], { stdio: 'ignore' })

async function waitForCdp() {
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch(`http://127.0.0.1:${PORT}/json/version`)
      if (r.ok) return await r.json()
    } catch {
      /* 还没起来 */
    }
    await sleep(250)
  }
  throw new Error('CDP 未就绪')
}
await waitForCdp()

/* ---------- 3) 连上页面 target ---------- */
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

await send('Page.enable')
await send('Runtime.enable')

/* ---------- 4) 注入 token 后导航到目标路由 ---------- */
await send('Page.navigate', { url: `${APP}/login` })
await sleep(1500)
await send('Runtime.evaluate', {
  expression: `localStorage.setItem('campusswap.token', ${JSON.stringify(token)})`
})
await send('Page.navigate', { url: `${APP}${route}` })
await sleep(3200) // 等前端把接口数据拉回来（本地后端，2~3 秒足够）

/* ---------- 5) 截图 ---------- */
const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
const out = `${SHOT_DIR}\\m5-${name}.png`
writeFileSync(out, Buffer.from(shot.data, 'base64'))
console.log(`截图已保存：${out}`)

ws.close()
edge.kill()
await sleep(500)
rmSync(profileDir, { recursive: true, force: true })
