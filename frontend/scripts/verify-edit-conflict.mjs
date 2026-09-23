// =============================================================================
//  §8.5 版本冲突（409）端到端复现脚本 —— 零依赖，Node 内置 fetch + CDP。
//
//  为什么必须专门验证：这一条是 M5 唯一"必须真的失败一次"的交互 ——
//  ① 打开编辑页时记下 versionNum；
//  ② 期间别人改了同一篇（这里由脚本用真接口改一次来模拟）；
//  ③ 点「保存草稿」→ 后端 409「该文档已被他人修改（当前版本 vN），请刷新后重试」；
//  ④ 前端**不切只读**，顶部出现 .conflict-bar 横幅 +「刷新内容」；
//  ⑤ 点「刷新内容」→ 拉回最新版覆盖表单 → 再保存成功（v 再 +1）。
//
//  用法：node scripts/verify-edit-conflict.mjs [文档ID] [身份]
//  产物：D:\DevEnv\logs\shots\m5-conflict-1-banner.png（冲突横幅）
//        D:\DevEnv\logs\shots\m5-conflict-2-recovered.png（刷新后保存成功）
//  注意：脚本会真的修改一篇文档（版本号 +2）并写两条版本留痕；
//        跑完若要回到干净演示数据，重跑 backend/sql/schema.sql + data.sql。
// =============================================================================
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const APP = 'http://localhost:5173'
const API = 'http://localhost:10087/api'
const SHOT_DIR = 'D:\\DevEnv\\logs\\shots'
const PORT = 9334

const ACCOUNTS = {
  admin: { username: 'admin', password: 'Admin@123' },
  docadmin: { username: 'docadmin', password: 'Doc@123456' },
  staff: { username: 'staff', password: 'Staff@123' }
}

const [docId = '3', who = 'staff'] = process.argv.slice(2)
const account = ACCOUNTS[who]
if (!account) {
  console.error(`未知身份 ${who}`)
  process.exit(1)
}
mkdirSync(SHOT_DIR, { recursive: true })

const results = []
function check(name, ok, detail) {
  results.push({ name, ok, detail })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`)
}

/* ---------- 1) 登录 + 读详情 ---------- */
const login = await (await fetch(`${API}/auth/login`, {
  method: 'POST',
  headers: { 'Content-Type': 'application/json; charset=utf-8' },
  body: JSON.stringify(account)
})).json()
if (login.code !== 200) {
  console.error('登录失败：', login.message)
  process.exit(1)
}
const token = login.data.token
const auth = { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json; charset=utf-8' }

const detailRes = await (await fetch(`${API}/documents/${docId}`, { headers: auth })).json()
if (detailRes.code !== 200) {
  console.error('读详情失败：', detailRes.message)
  process.exit(1)
}
const before = detailRes.data
console.log(`文档 ${docId}「${before.title}」当前 v${before.versionNum}，状态 ${before.status}，canEdit=${before.canEdit}`)
if (!before.canEdit) {
  console.error('这篇文档当前身份不可编辑，换一篇（例如 staff → 3）')
  process.exit(1)
}

/* ---------- 2) 起 Edge 并进编辑页（此刻页面持有 v{before.versionNum}） ---------- */
const profileDir = `${SHOT_DIR}\\cdpprof-conflict-${process.pid}`
rmSync(profileDir, { recursive: true, force: true })
const edge = spawn(EDGE, [
  '--headless=new', '--disable-gpu', '--hide-scrollbars', '--no-first-run', '--no-default-browser-check',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profileDir}`, '--window-size=1600,1400', 'about:blank'
], { stdio: 'ignore' })

async function waitForCdp() {
  for (let i = 0; i < 40; i++) {
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
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
  return r.result?.value
}
async function shot(name) {
  const s = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  const out = `${SHOT_DIR}\\${name}.png`
  writeFileSync(out, Buffer.from(s.data, 'base64'))
  console.log(`  截图：${out}`)
  return out
}

await send('Page.enable')
await send('Runtime.enable')
await send('Page.navigate', { url: `${APP}/login` })
await sleep(1500)
await evaluate(`localStorage.setItem('campusswap.token', ${JSON.stringify(token)})`)
await send('Page.navigate', { url: `${APP}/docs/edit/${docId}` })
await sleep(3200)

const loadedVersion = await evaluate(`(document.querySelector('.page-head .desc')||{}).textContent||''`)
check('编辑页已加载并显示版本号', String(loadedVersion).includes(`v${before.versionNum}`), String(loadedVersion).trim().slice(0, 60))

/* ---------- 3) 模拟"别人改了这篇"：带当前版本号真改一次 ---------- */
const body = {
  id: String(docId),
  versionNum: before.versionNum,
  title: before.title,
  summary: before.summary,
  contentMd: before.contentMd,
  categoryId: before.categoryId,
  tagIds: (before.tags ?? []).map((t) => t.id),
  priceCents: before.priceCents
}
const bump = await (await fetch(`${API}/documents/${docId}`, { method: 'PUT', headers: auth, body: JSON.stringify(body) })).json()
check('模拟他人修改成功（版本 +1）', bump.code === 200 && bump.data.versionNum === before.versionNum + 1,
  `code=${bump.code} v=${bump.data?.versionNum} ${bump.message ?? ''}`)

/* ---------- 4) 点「保存草稿」→ 期望 409 横幅、且不切只读 ---------- */
await evaluate(`[...document.querySelectorAll('.page-head .acts button')].find(b=>b.textContent.trim()==='保存草稿')?.click()`)
await sleep(2200)
const banner = await evaluate(`(document.querySelector('.conflict-bar')||{}).textContent||''`)
const bannerShot = await shot('m5-conflict-1-banner')
check('出现版本冲突横幅 .conflict-bar', /已被他人修改/.test(String(banner)) && /当前版本 v\d+/.test(String(banner)), String(banner).trim().slice(0, 80))
check('横幅带「刷新内容」按钮', /刷新内容/.test(String(banner)))
const stillEditable = await evaluate(`!document.querySelector('.ta')?.disabled && !document.querySelector('.alert.a-warn')`)
check('未切换为只读（正文仍可编辑、无只读横幅）', stillEditable === true)
const toastShot = await evaluate(`(document.querySelector('.toast-host')||{}).textContent||''`)
check('同时给了轻提示 toast', /已被他人修改/.test(String(toastShot)), String(toastShot).trim().slice(0, 50))

/* ---------- 5) 点「刷新内容」→ 覆盖表单后再保存成功 ---------- */
await evaluate(`[...document.querySelectorAll('.conflict-bar button')].find(b=>b.textContent.trim()==='刷新内容')?.click()`)
await sleep(1800)
const afterRefresh = await evaluate(`(document.querySelector('.page-head .desc')||{}).textContent||''`)
check('刷新后页面版本号已更新', String(afterRefresh).includes(`v${before.versionNum + 1}`), String(afterRefresh).trim().slice(0, 60))
const bannerGone = await evaluate(`!document.querySelector('.conflict-bar')`)
check('刷新后横幅消失', bannerGone === true)

await evaluate(`[...document.querySelectorAll('.page-head .acts button')].find(b=>b.textContent.trim()==='保存草稿')?.click()`)
await sleep(2200)
const savedToast = await evaluate(`(document.querySelector('.toast-host')||{}).textContent||''`)
const recoveredShot = await shot('m5-conflict-2-recovered')
check('刷新后保存成功（版本再 +1）', new RegExp(`v${before.versionNum + 2}`).test(String(savedToast)), String(savedToast).trim().slice(0, 60))

const finalRes = await (await fetch(`${API}/documents/${docId}`, { headers: auth })).json()
check('库中版本号 = 初始 +2', finalRes.data.versionNum === before.versionNum + 2, `v${finalRes.data.versionNum}`)

ws.close()
edge.kill()
await sleep(500)
rmSync(profileDir, { recursive: true, force: true })

const failed = results.filter((r) => !r.ok)
console.log(`\nRESULT  pass=${results.length - failed.length} fail=${failed.length}`)
if (failed.length) process.exit(1)
console.log(`截图：${bannerShot} / ${recoveredShot}`)
