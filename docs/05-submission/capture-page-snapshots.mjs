// 用 Chrome DevTools Protocol 抓取前端每个路由的"渲染后 DOM + 内联 CSS"与截图，
// 生成可以离线双击打开的页面快照（前端源码包附加材料用）。
// 依赖：Node 22+ 内置 WebSocket（无需 npm install）、本机 Chrome、前端 dev server(5173)、后端(10087)。
import { spawn } from 'node:child_process'
import { mkdirSync, writeFileSync, rmSync } from 'node:fs'
import { join } from 'node:path'
import { setTimeout as sleep } from 'node:timers/promises'

const CHROME = 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'
const PORT = 9222
const BASE = 'http://localhost:5173'
const OUT = process.argv[2] || 'D:\\Six Gods\\Desktop\\交付-源码分包\\_shots'
const PROFILE = join(process.env.TEMP || 'C:\\Windows\\Temp', 'cdp-profile-' + Date.now())

// 需要抓取的路由：路径 → 输出文件名 + 中文标题
const ROUTES = [
  ['/login', 'login', '登录页'],
  ['/workbench', 'workbench', '工作台'],
  ['/docs', 'docs', '文档检索（含组合筛选与高亮）'],
  ['/docs/edit', 'docs-edit', '文档编辑（Markdown 实时预览）'],
  ['/my', 'my', '我的文档'],
  ['/review', 'review', '待我审核'],
  ['/governance', 'governance', '内容治理'],
  ['/taxonomy', 'taxonomy', '分类与标签'],
  ['/admin/users', 'users', '用户管理'],
  ['/admin/roles', 'roles', '角色与权限'],
  ['/admin/org', 'org', '组织机构'],
  ['/admin/logs', 'logs', '操作日志'],
  ['/me', 'me', '我的资料']
]

/** 极简 CDP 客户端：把 send(method, params) 包装成 Promise。 */
class Cdp {
  constructor(ws) {
    this.ws = ws
    this.id = 0
    this.waiters = new Map()
    ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.waiters.has(msg.id)) {
        const { resolve, reject } = this.waiters.get(msg.id)
        this.waiters.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
      }
    })
  }
  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.waiters.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
      setTimeout(() => {
        if (this.waiters.has(id)) {
          this.waiters.delete(id)
          reject(new Error('CDP timeout: ' + method))
        }
      }, 30000)
    })
  }
}

async function fetchJson(url) {
  const res = await fetch(url)
  return res.json()
}

async function main() {
  rmSync(PROFILE, { recursive: true, force: true })
  const chrome = spawn(
    CHROME,
    [
      '--headless=new',
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=${PROFILE}`,
      '--window-size=1680,1050',
      '--hide-scrollbars',
      '--no-first-run',
      '--no-default-browser-check',
      '--disable-gpu',
      'about:blank'
    ],
    { stdio: 'ignore', detached: false }
  )

  // 等调试端口起来
  let target = null
  for (let i = 0; i < 40; i++) {
    await sleep(500)
    try {
      const list = await fetchJson(`http://127.0.0.1:${PORT}/json/list`)
      target = list.find((t) => t.type === 'page')
      if (target) break
    } catch {
      /* 还没起来 */
    }
  }
  if (!target) throw new Error('无法连接 Chrome 调试端口')

  const ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve)
    ws.addEventListener('error', () => reject(new Error('WebSocket 连接失败')))
  })
  const cdp = new Cdp(ws)
  await cdp.send('Page.enable')
  await cdp.send('Runtime.enable')

  mkdirSync(join(OUT, '快照'), { recursive: true })
  mkdirSync(join(OUT, '截图'), { recursive: true })

  // 先登录拿 token（后端真实接口），再写进 localStorage
  const loginRes = await fetch('http://localhost:10087/api/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@123' })
  })
  const loginJson = await loginRes.json()
  const token = loginJson?.data?.token
  if (!token) throw new Error('登录失败，未拿到 token: ' + JSON.stringify(loginJson).slice(0, 200))

  // 先打开首页以便设置 localStorage（同源才有效）
  await cdp.send('Page.navigate', { url: BASE + '/login' })
  await sleep(2500)
  await cdp.send('Runtime.evaluate', {
    expression: `localStorage.setItem('campusswap.token', ${JSON.stringify(token)}); 'ok'`
  })

  const report = []
  for (const [path, file, title] of ROUTES) {
    await cdp.send('Page.navigate', { url: BASE + path })
    await sleep(path === '/docs' ? 4000 : 2600)

    // 抓"渲染后 DOM + 内联 CSS"，并在顶部插一条说明条
    const expr = `(() => {
      const css = Array.from(document.styleSheets).map(s => {
        try { return Array.from(s.cssRules).map(r => r.cssText).join('\\n') } catch (e) { return '' }
      }).join('\\n');
      const banner = '<div style="position:sticky;top:0;z-index:99999;background:#1e3a8a;color:#fff;font:13px/1.6 -apple-system,Segoe UI,Microsoft YaHei,sans-serif;padding:8px 14px">'
        + '离线静态快照 · ${title}（${path}）· 由真实运行的前端抓取，交互按钮不可用；数据来自本机 MySQL + Elasticsearch</div>';
      const html = document.documentElement.outerHTML
        .replace(/<script[\\s\\S]*?<\\/script>/gi, '')
        .replace(/<link[^>]+rel="stylesheet"[^>]*>/gi, '');
      return JSON.stringify({ css, html: html.replace(/(<body[^>]*>)/i, '$1' + banner) });
    })()`
    const dom = await cdp.send('Runtime.evaluate', { expression: expr, returnByValue: true })
    const { css, html } = JSON.parse(dom.result.value)
    const page = `<!DOCTYPE html>\n<html lang="zh-CN">\n<head>\n<meta charset="utf-8">\n<title>${title} · CampusSwap 页面快照</title>\n<style>\n${css}\n</style>\n</head>\n${html.replace(/^<html[^>]*>/i, '')}`
    writeFileSync(join(OUT, '快照', file + '.html'), page, 'utf8')

    const shot = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false })
    writeFileSync(join(OUT, '截图', file + '.png'), Buffer.from(shot.data, 'base64'))
    report.push({ path, file, title, htmlKb: Math.round(page.length / 1024) })
    console.log(`  ✓ ${path}  → 快照/${file}.html (${Math.round(page.length / 1024)} KB) + 截图/${file}.png`)
  }

  // 生成画廊
  const cards = report
    .map(
      (r) =>
        `    <figure><a href="快照/${r.file}.html" target="_blank"><img src="截图/${r.file}.png" alt="${r.title}" loading="lazy"></a>` +
        `<figcaption><b>${r.title}</b><span>${r.path} · <a href="快照/${r.file}.html">离线快照</a></span></figcaption></figure>`
    )
    .join('\n')
  const gallery = `<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>CampusSwap 师大文档管理平台 · 页面展示</title>
<style>
  body{margin:0;font:15px/1.7 -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;background:#0f172a;color:#e2e8f0}
  header{padding:26px 32px;background:linear-gradient(120deg,#1e3a8a,#0e7490);color:#fff}
  header h1{margin:0 0 6px;font-size:23px} header p{margin:0;opacity:.92;font-size:13.5px}
  main{padding:22px 32px 60px}
  .tip{background:#1e293b;border-left:4px solid #38bdf8;padding:12px 16px;border-radius:8px;font-size:13.5px;margin-bottom:20px}
  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(380px,1fr));gap:18px}
  figure{margin:0;background:#1e293b;border-radius:12px;overflow:hidden;box-shadow:0 6px 18px rgba(0,0,0,.35)}
  figure img{width:100%;display:block;transition:transform .2s} figure img:hover{transform:scale(1.02)}
  figcaption{padding:10px 14px;font-size:13.5px;display:flex;justify-content:space-between;gap:10px;align-items:baseline}
  figcaption span{color:#94a3b8;font-size:12px} a{color:#7dd3fc}
</style>
</head>
<body>
<header>
  <h1>CampusSwap 师大文档管理平台 · 页面展示</h1>
  <p>前端源码包附加材料：<b>每个页面都可点开离线快照</b>（渲染后的真实 DOM + 内联样式，双击即看，无需后端）</p>
</header>
<main>
  <div class="tip"><b>怎么看：</b>点任意一张图 → 打开该页面的离线快照；快照是真实运行时抓取的静态副本，按钮点击等交互不响应。要看可交互效果请启动前后端后访问 http://localhost:5173（演示账号 admin / Admin@123）。</div>
  <div class="grid">
${cards}
  </div>
</main>
</body>
</html>
`
  writeFileSync(join(OUT, 'index.html'), gallery, 'utf8')
  console.log(`\n  画廊已生成：${report.length} 个页面`)
  ws.close()
  chrome.kill()
  await sleep(500)
  rmSync(PROFILE, { recursive: true, force: true })
}

main().catch((e) => {
  console.error('抓取失败: ' + e.message)
  process.exit(1)
})
