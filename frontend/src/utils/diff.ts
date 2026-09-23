/**
 * 行级差异（审核页的「版本并排对比」用）。
 *
 * <p>不用第三方 diff 库：正文是 Markdown 纯文本，行数在几百行量级，一段 LCS 动态规划足够，
 * 也避免为了一个展示功能引入依赖。超大文本会被截断（见 `MAX_LINES`），保证 UI 不卡。</p>
 */

/** 差异行的类型。 */
export type DiffKind = 'same' | 'add' | 'del'

/** 一行差异。 */
export interface DiffLine {
  kind: DiffKind
  text: string
  /** 在旧文中的行号（从 1 起；新增行为 null）。 */
  oldNo: number | null
  /** 在新文中的行号（从 1 起；删除行为 null）。 */
  newNo: number | null
}

/** 超过这个行数就只比前 N 行（避免 O(n²) 在极端输入上拖住主线程）。 */
const MAX_LINES = 600

/** 差异统计。 */
export interface DiffSummary {
  add: number
  del: number
  /** 新文相对旧文的字符数变化（正 = 变长）。 */
  charDelta: number
}

/**
 * 逐行对比两段文本。
 *
 * @param oldText 旧文本（可为空）
 * @param newText 新文本（可为空）
 * @returns 差异行（顺序即合并后的阅读顺序）
 */
export function diffLines(oldText: string | null | undefined, newText: string | null | undefined): DiffLine[] {
  const a = splitLines(oldText)
  const b = splitLines(newText)
  const n = a.length
  const m = b.length
  // dp[i][j] = a[i..] 与 b[j..] 的最长公共子序列长度
  const dp: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: DiffLine[] = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ kind: 'same', text: a[i], oldNo: i + 1, newNo: j + 1 })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      out.push({ kind: 'del', text: a[i], oldNo: i + 1, newNo: null })
      i++
    } else {
      out.push({ kind: 'add', text: b[j], oldNo: null, newNo: j + 1 })
      j++
    }
  }
  while (i < n) out.push({ kind: 'del', text: a[i], oldNo: ++i, newNo: null })
  while (j < m) out.push({ kind: 'add', text: b[j], oldNo: null, newNo: ++j })
  return out
}

/**
 * 差异统计（审核页的「本次变更」摘要用）。
 *
 * @param oldText 旧文本
 * @param newText 新文本
 * @returns 新增行数 / 删除行数 / 字符数变化
 */
export function diffSummary(oldText: string | null | undefined, newText: string | null | undefined): DiffSummary {
  const lines = diffLines(oldText, newText)
  let add = 0
  let del = 0
  for (const l of lines) {
    if (l.kind === 'add') add++
    else if (l.kind === 'del') del++
  }
  const oldLen = (oldText ?? '').length
  const newLen = (newText ?? '').length
  return { add, del, charDelta: newLen - oldLen }
}

/**
 * 拆行（同时裁掉超出上限的尾部）。
 *
 * @param text 文本
 * @returns 行数组
 */
function splitLines(text: string | null | undefined): string[] {
  if (!text) return []
  const lines = text.replace(/\r\n/g, '\n').split('\n')
  if (lines.length <= MAX_LINES) return lines
  return [...lines.slice(0, MAX_LINES), `……（还有 ${lines.length - MAX_LINES} 行未参与对比）`]
}
