#!/usr/bin/env node
/**
 * M6-T6.7 逐接口 SQL 条数点数器
 * ---------------------------------------------------------------------------
 * 对应 docs/02-design/ARCHITECTURE.md §10.5「每个读接口的 SQL 条数预算」。
 * 判据是**常数**：条数必须与 pageSize（以及数据量）无关；本条数超预算即 N+1 缺陷。
 *
 * 原理：dev 配置开着 spring.jpa.show-sql + format_sql，后端 stdout 经
 *   D:\DevEnv\scripts\campusswap-backend.cmd 的 Tee-Object 落到
 *   D:\DevEnv\logs\campusswap-app.log。
 * 本脚本在每个请求前后按**字节偏移**读取日志新增部分，数「以 Hibernate: 开头的行」
 * —— 一行 = 一条 SQL（format_sql 只影响换行缩进，不影响前缀行数）。
 * 请求串行发出：测量期间请勿同时跑别的检查器（否则会把别人的 SQL 算进来）。
 *
 * 用法（Node 24）：
 *   F:\node\node.exe docs/03-qa-review/probe-sql-counts.mjs
 *   F:\node\node.exe docs/03-qa-review/probe-sql-counts.mjs --compare <a.json> <b.json>
 *
 * 环境变量：
 *   CS_BASE     后端地址，默认 http://127.0.0.1:10087
 *   CS_APP_LOG  后端日志，默认 D:\DevEnv\logs\campusswap-app.log
 *   CS_TAG      本轮标签（写进 JSON 与文件名），默认 run
 *   CS_SETTLE   每条测量等待日志落盘的毫秒数，默认 600
 *
 * 退出码：0 = 全部达标；1 = 有超预算项或接口报错。
 */

import fs from 'node:fs'
import path from 'node:path'

const BASE = process.env.CS_BASE ?? 'http://127.0.0.1:10087'
const LOG = process.env.CS_APP_LOG ?? 'D:\\DevEnv\\logs\\campusswap-app.log'
const TAG = process.env.CS_TAG ?? 'run'
const SETTLE = Number(process.env.CS_SETTLE ?? 600)
const OUT_DIR = 'D:\\DevEnv\\logs'

const ACCOUNTS = {
  admin: ['admin', 'Admin@123'],
  docadmin: ['docadmin', 'Doc@123456'],
  staff: ['staff', 'Staff@123'],
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

/** 发一个请求，返回 {status, json, raw}（4xx/5xx 不抛异常，便于断言错误路径）。 */
async function api(method, urlPath, token, body) {
  const headers = {}
  if (token) headers.Authorization = `Bearer ${token}`
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  const res = await fetch(BASE + urlPath, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const raw = await res.text()
  let json = null
  try {
    json = JSON.parse(raw)
  } catch {
    /* 空响应体（如拦截器直接 401）保留 raw 供诊断 */
  }
  return { status: res.status, json, raw }
}

/**
 * 日志编码探测：PowerShell 5.1 的 Tee-Object 默认写 UTF-16LE（带 FF FE BOM），
 * 且每个字符 2 字节 —— 所以按字节偏移读取后必须用对应编码解码，
 * 且偏移要对齐到码元宽度（踩坑记录：用 utf8 解码整段全是 NUL，正则一条都匹配不到）。
 */
function detectEncoding() {
  const buf = Buffer.allocUnsafe(4)
  const fd = fs.openSync(LOG, 'r')
  try {
    fs.readSync(fd, buf, 0, 4, 0)
  } finally {
    fs.closeSync(fd)
  }
  if (buf[0] === 0xff && buf[1] === 0xfe) return { name: 'utf16le', unit: 2 }
  if (buf[0] === 0xef && buf[1] === 0xbb) return { name: 'utf8', unit: 1 }
  return { name: 'utf8', unit: 1 }
}

const ENC = detectEncoding()

/** 读取日志 [from, 当前末尾) 的字节并按探测到的编码解码。 */
function readChunk(from) {
  let start = from
  if (ENC.unit === 2) start -= start % 2 // 对齐到 UTF-16 码元边界
  const size = fs.statSync(LOG).size
  if (size <= start) return ''
  const len = size - start
  const buf = Buffer.allocUnsafe(len)
  const fd = fs.openSync(LOG, 'r')
  try {
    fs.readSync(fd, buf, 0, len, start)
  } finally {
    fs.closeSync(fd)
  }
  return buf.toString(ENC.name)
}

async function readSettled(from) {
  let text = ''
  for (let i = 0; i < 8; i++) {
    await sleep(i === 0 ? SETTLE : 150)
    text = readChunk(from)
    if (text.length > 0 && !text.endsWith('\n')) continue // 半行，再等
    return text
  }
  return text
}

const countSql = (text) => (text.match(/^Hibernate:/gm) ?? []).length

/** 把日志片段里的每条 SQL 摘成一行（去掉 format_sql 的缩进与参数占位行）。 */
function sqlDigest(text) {
  const lines = text.split(/\r?\n/)
  const out = []
  for (let i = 0; i < lines.length; i++) {
    if (!lines[i].startsWith('Hibernate:')) continue
    const stmt = lines
      .slice(i, i + 12)
      .join(' ')
      .replace(/Hibernate:\s*/, '')
      .replace(/\s+/g, ' ')
      .trim()
    out.push(stmt.slice(0, 110))
  }
  return out
}

/**
 * 排空日志尾部：等到连续两次读取都没有新增字节为止。
 * 必须有这一步 —— 上一个请求（尤其是登录/预取）的日志可能还在 Tee 的缓冲里，
 * 会算到下一个请求头上（踩坑记录：检索列表 pageSize=1 首轮报 5 条，实际 4 条）。
 */
async function drain() {
  for (let i = 0; i < 10; i++) {
    const a = fs.statSync(LOG).size
    await sleep(200)
    const b = fs.statSync(LOG).size
    if (a === b) return
  }
}

/** 测量一次请求消耗的 SQL 条数。 */
async function measure(label, { role, method = 'GET', urlPath, body }) {
  const token = await tokenOf(role)
  await drain()
  const from = fs.statSync(LOG).size
  const res = await api(method, urlPath, token, body)
  const chunk = await readSettled(from)
  return {
    label,
    method,
    urlPath,
    role,
    status: res.status,
    sql: countSql(chunk),
    statements: sqlDigest(chunk),
    total: res.json?.data?.total ?? null,
    json: res.json,
    raw: res.raw,
  }
}

const tokenCache = new Map()
async function tokenOf(role) {
  if (tokenCache.has(role)) return tokenCache.get(role)
  const [u, p] = ACCOUNTS[role]
  const res = await api('POST', '/api/auth/login', null, { username: u, password: p })
  if (res.status !== 200 || !res.json?.data?.token) {
    throw new Error(`登录失败 ${role}: HTTP ${res.status} ${res.raw?.slice(0, 200)}`)
  }
  tokenCache.set(role, res.json.data.token)
  return res.json.data.token
}

/** 预取动态 ID：分类、角色、部门、他人已发布文档、自己已发布文档。 */
async function recon() {
  const h = (r) => ({ role: r })
  const cats = await api('GET', '/api/categories/tree', await tokenOf('staff'), undefined)
  const catId = cats.json?.data?.[0]?.id ?? '1'
  const roles = await api('GET', '/api/roles?pageNum=1&pageSize=10', await tokenOf('admin'), undefined)
  const roleList = roles.json?.data?.list ?? []
  const roleId = (roleList.find((r) => r.code === 'DOC_ADMIN') ?? roleList[0])?.id ?? '2'
  const depts = await api('GET', '/api/depts/tree', await tokenOf('admin'), undefined)
  const deptId = depts.json?.data?.[0]?.id ?? '1'
  const mine = await api('GET', '/api/documents/mine?pageNum=1&pageSize=50', await tokenOf('staff'), undefined)
  const myPub = (mine.json?.data?.list ?? []).find((d) => d.status === 'PUBLISHED')
  const others = await api('GET', '/api/documents?pageNum=1&pageSize=50', await tokenOf('staff'), undefined)
  const otherPub = (others.json?.data?.list ?? []).find((d) => d.authorId !== '3' && d.status === 'PUBLISHED')
  if (!myPub || !otherPub) throw new Error('探针预取失败：种子库里找不到 staff 自己的已发布文档或他人的已发布文档')
  // 关键词必须真的命中：取一篇库里文档标题里的连续 2 个汉字（≥2 字才走全文检索分支）
  const cjk = /[\u4e00-\u9fa5]{2}/
  const kwMatch = (others.json?.data?.list ?? []).map((d) => cjk.exec(d.title ?? '')).find(Boolean)
  if (!kwMatch) throw new Error('探针预取失败：库内标题里找不到连续 2 个汉字，无法测全文检索分支')
  const keyword = kwMatch[0]
  return { catId, roleId, deptId, myPubId: myPub.id, otherPubId: otherPub.id, keyword }
}

/** 规格表：paginated=true 的接口测 pageSize=1 与 pageSize=100 两档，验证「常数」。 */
function specs(ctx) {
  const P = (extra) => (n) => `/api/documents?pageNum=1&pageSize=${n}${extra}`
  return [
    { name: '检索列表·关键词为空', role: 'staff', paginated: true, budget: '3~5', hi: 5, path: P('') },
    { name: '检索列表·关键词≥2字（全文分支）', role: 'staff', paginated: true, budget: '3~5', hi: 5, path: P(`&keyword=${encodeURIComponent(ctx.keyword)}`) },
    { name: '检索列表·带 categoryId', role: 'staff', paginated: true, budget: '3~5', hi: 5, path: P(`&categoryId=${ctx.catId}`) },
    { name: '治理列表 /documents/manage', role: 'docadmin', paginated: true, budget: '3~5', hi: 5, path: (n) => `/api/documents/manage?pageNum=1&pageSize=${n}` },
    { name: '我的文档 /documents/mine', role: 'staff', paginated: true, budget: '3', hi: 3, path: (n) => `/api/documents/mine?pageNum=1&pageSize=${n}` },
    { name: '审核队列 /review/documents', role: 'docadmin', paginated: true, budget: '3', hi: 3, path: (n) => `/api/review/documents?pageNum=1&pageSize=${n}` },
    { name: '我的收藏 /favorites', role: 'staff', paginated: true, budget: '3', hi: 3, path: (n) => `/api/favorites?pageNum=1&pageSize=${n}` },
    { name: '用户列表 /users', role: 'admin', paginated: true, budget: '4', hi: 4, path: (n) => `/api/users?pageNum=1&pageSize=${n}` },
    { name: '文档详情 /documents/{id}', role: 'staff', paginated: false, budget: '5（含首次访问阅读量自增）', hi: 5, path: () => `/api/documents/${ctx.otherPubId}` },
    { name: '角色列表 /roles', role: 'admin', paginated: false, budget: '1', hi: 1, path: () => '/api/roles?pageNum=1&pageSize=10' },
    { name: '权限树 /permissions/tree', role: 'admin', paginated: false, budget: '1', hi: 1, path: () => '/api/permissions/tree' },
    { name: '回收站 /documents/trash', role: 'staff', paginated: false, budget: '1~3', hi: 3, path: () => '/api/documents/trash?pageNum=1&pageSize=10' },
    { name: '标签列表 /tags', role: 'staff', paginated: false, budget: '1', hi: 1, path: () => '/api/tags?pageNum=1&pageSize=10' },
    { name: '部门树 /depts/tree', role: 'admin', paginated: false, budget: '1', hi: 1, path: () => '/api/depts/tree' },
    { name: '分类树 /categories/tree', role: 'staff', paginated: false, budget: '1', hi: 1, path: () => '/api/categories/tree' },
    { name: '角色权限 /roles/{id}/permissions', role: 'admin', paginated: false, budget: '2', hi: 2, path: (n, c) => `/api/roles/${c.roleId}/permissions` },
    { name: '部门角色 /depts/{id}/roles', role: 'admin', paginated: false, budget: '2', hi: 2, path: (n, c) => `/api/depts/${c.deptId}/roles` },
    { name: '统计概览 /stats/overview', role: 'staff', paginated: false, budget: '1', hi: 1, path: () => '/api/stats/overview' },
  ]
}

function pad(s, n) {
  const w = [...String(s)].reduce((a, ch) => a + (ch.charCodeAt(0) > 0x2e80 ? 2 : 1), 0)
  return String(s) + ' '.repeat(Math.max(0, n - w))
}

async function compare(fa, fb) {
  const a = JSON.parse(fs.readFileSync(fa, 'utf8'))
  const b = JSON.parse(fs.readFileSync(fb, 'utf8'))
  console.log(`\n数据量无关性对照：${a.tag}（${a.docTotal} 篇）→ ${b.tag}（${b.docTotal} 篇）\n`)
  console.log(
    pad('接口', 40) + pad('SQL@1', 8) + pad('SQL@100', 8) + pad('SQL@1', 8) + pad('SQL@100', 9) + '一致?',
  )
  let same = true
  for (const ra of a.rows) {
    const rb = b.rows.find((r) => r.label === ra.label)
    if (!rb) continue
    // 允差 1：pageSize 那一档是否"满页"会决定 PageableExecutionUtils 要不要发分页 count，
    // 而满页与否取决于 total 与 pageSize 的关系（数据量本身不改变条数量级）。
    const d1 = ra.sql1 === null || rb.sql1 === null ? 0 : Math.abs(ra.sql1 - rb.sql1)
    const d100 = ra.sql100 === null || rb.sql100 === null ? 0 : Math.abs(ra.sql100 - rb.sql100)
    const eq = d1 <= 1 && d100 <= 1
    if (!eq) same = false
    console.log(
      pad(ra.label, 40) +
        pad(ra.sql1 ?? '-', 8) +
        pad(ra.sql100 ?? '-', 8) +
        pad(rb.sql1 ?? '-', 8) +
        pad(rb.sql100 ?? '-', 9) +
        (eq ? (d1 + d100 === 0 ? 'OK' : 'OK(+count)') : 'CHANGED'),
    )
  }
  console.log(
    `\n结论：${same ? '所有接口 SQL 条数与数据量无关（同一形状下逐条相等；±1 只来自分页 count 是否触发）' : '有接口条数随数据量变化，需排查 N+1'}`,
  )
  process.exit(same ? 0 : 1)
}

async function main() {
  const argv = process.argv.slice(2)
  if (argv[0] === '--compare') return compare(argv[1], argv[2])

  if (!fs.existsSync(LOG)) throw new Error(`后端日志不存在：${LOG}（后端必须由 campusswap-backend.cmd 启动）`)
  const ctx = await recon()
  const docTotalRes = await api('GET', '/api/documents/manage?pageNum=1&pageSize=1', await tokenOf('admin'), undefined)
  const docTotal = docTotalRes.json?.data?.total ?? -1

  const rows = []
  let fails = 0
  console.log(`\nSQL 条数点数（tag=${TAG}，库内文档 ${docTotal} 篇，后端 ${BASE}）`)
  console.log(pad('接口', 40) + pad('pageSize=1', 11) + pad('pageSize=100', 12) + pad('预算', 22) + '判定')
  console.log('-'.repeat(100))

  for (const spec of specs(ctx)) {
    let m1 = null
    let m100 = null
    if (spec.paginated) {
      m1 = await measure(spec.name, { role: spec.role, urlPath: spec.path(1, ctx) })
      m100 = await measure(spec.name, { role: spec.role, urlPath: spec.path(100, ctx) })
    } else {
      m1 = await measure(spec.name, { role: spec.role, urlPath: spec.path(null, ctx) })
    }
    const sql1 = m1.sql
    const sql100 = m100 ? m100.sql : null
    const worst = sql100 === null ? sql1 : Math.max(sql1, sql100)
    // 允差 1：满页时 PageableExecutionUtils 会多发一条分页 count（ARCHITECTURE §10.5 注）
    const budgetOk = worst <= spec.hi + 1
    const delta = sql100 === null ? 0 : Math.abs(sql100 - sql1)
    const constOk = delta <= 1
    const httpOk = m1.status === 200 && (!m100 || m100.status === 200)
    const ok = budgetOk && constOk && httpOk
    if (!ok) fails++
    rows.push({
      label: spec.name,
      role: spec.role,
      path: spec.paginated ? spec.path(100, ctx) : spec.path(null, ctx),
      budget: spec.budget,
      sql1,
      sql100,
      worst,
      delta,
      ok,
      total: m1.total,
      status: [m1.status, m100 ? m100.status : null],
      statements: m1.statements,
      statements100: m100 ? m100.statements : null,
    })
    console.log(
      pad(spec.name, 40) +
        pad(sql1, 11) +
        pad(sql100 === null ? '—' : sql100, 12) +
        pad(spec.budget, 22) +
        (ok ? 'PASS' : `FAIL(${!httpOk ? 'HTTP ' + m1.status : !budgetOk ? '超预算' : '非常数'})`),
    )
    if (!ok || process.env.CS_VERBOSE === '1') {
      const tag = m100 ? `pageSize=1 实测 ${sql1} 条` : `实测 ${sql1} 条`
      console.log(`    └─ ${tag}（返回 total=${m1.total ?? '-'}）`)
      m1.statements.forEach((s, i) => console.log(`       ${i + 1}. ${s}`))
      if (m100) {
        console.log(`    └─ pageSize=100 实测 ${sql100} 条（返回 total=${m100.total ?? '-'}）`)
        m100.statements.forEach((s, i) => console.log(`       ${i + 1}. ${s}`))
      }
    }
  }

  console.log('-'.repeat(100))
  console.log(`合计 ${rows.length} 个读接口；超预算/异常 ${fails} 个`)

  const outFile = path.join(OUT_DIR, `sql-counts-${TAG}.json`)
  fs.writeFileSync(outFile, JSON.stringify({ tag: TAG, base: BASE, docTotal, at: new Date().toISOString(), rows }, null, 2), 'utf8')
  console.log(`明细已写：${outFile}`)
  console.log(fails === 0 ? 'RESULT: PASS' : `RESULT: FAIL=${fails}`)
  process.exit(fails === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('探针异常：' + (e?.stack ?? e))
  process.exit(2)
})
