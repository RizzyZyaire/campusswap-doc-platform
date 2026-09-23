// Markdown 渲染（正文与编辑器预览共用）。
//
// 两道防线：
//  ① markdown-it 关掉 `html`（默认就关），原始 HTML 不会进渲染结果；
//  ② 再用 DOMPurify 过一遍，防住 markdown-it 生成的链接/属性里的注入。
// 前端只做"安全渲染"，不承担内容审核 —— 那由文档状态机与审核流程负责。
import MarkdownIt from 'markdown-it'
import DOMPurify from 'dompurify'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false
})

// 给 h1~h3 加稳定 id（`h-1`、`h-2`…按正文里出现的顺序），供右侧「本文目录」锚点跳转。
// 与 extractHeadings 用同一套编号规则：都按"第 n 个 1~3 级标题"数，两边必须一致。
const defaultHeadingOpen =
  md.renderer.rules.heading_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))

md.renderer.rules.heading_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const level = Number(token.tag.slice(1))
  if (level <= 3) {
    token.attrSet('id', `h-${headingSeq(tokens, idx)}`)
  }
  return defaultHeadingOpen(tokens, idx, options, env, self)
}

/** 该标题在全文里是第几个 1~3 级标题（从 1 起）。 */
function headingSeq(tokens: { tag: string; type: string }[], idx: number): number {
  let n = 0
  for (let i = 0; i <= idx; i++) {
    const t = tokens[i]
    if (t.type === 'heading_open' && Number(t.tag.slice(1)) <= 3) n++
  }
  return n
}

/** 目录项。 */
export interface HeadingItem {
  /** 与渲染结果里的 `id` 一致（`h-1`、`h-2`…）。 */
  id: string
  /** 标题层级（1~3）。 */
  level: number
  /** 标题纯文本。 */
  text: string
}

/**
 * 从**渲染结果**里抽取 1~3 级标题做「本文目录」。
 *
 * <p>刻意不解析 Markdown 源文：源文里的 `#` 可能落在围栏代码块或引用块里，
 * 手写规则迟早与渲染器不一致（编号一旦错位，锚点就点不动）。直接从 HTML 里取，
 * 编号与 id 天生一致。</p>
 *
 * @param html renderMarkdown 的产物
 * @returns 目录项（按出现顺序）
 */
export function extractHeadingsFromHtml(html: string): HeadingItem[] {
  if (!html) return []
  const out: HeadingItem[] = []
  const re = /<h([1-3]) id="(h-\d+)">([\s\S]*?)<\/h\1>/g
  let m
  while ((m = re.exec(html))) {
    const text = m[3]
      .replace(/<[^>]+>/g, '')
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .trim()
    if (text) out.push({ id: m[2], level: Number(m[1]), text })
  }
  return out
}

/**
 * 渲染并抽取目录（等价于 `extractHeadingsFromHtml(renderMarkdown(src))`）。
 *
 * @param source Markdown 源文
 * @returns 目录项
 */
export function extractHeadings(source: string | null | undefined): HeadingItem[] {
  return extractHeadingsFromHtml(renderMarkdown(source))
}

/**
 * 渲染 Markdown 为可安全插入的 HTML。
 *
 * @param source Markdown 源文（可空）
 * @returns 消毒后的 HTML 字符串
 */
export function renderMarkdown(source: string | null | undefined): string {
  if (!source) return ''
  return DOMPurify.sanitize(md.render(source), { USE_PROFILES: { html: true } })
}

/**
 * 生成编辑器工具栏要插入的片段。
 *
 * @param kind 片段类型
 * @param selected 当前选中的文本（用于包裹）
 * @returns 待插入文本
 */
export function markdownSnippet(kind: 'h1' | 'h2' | 'bold' | 'quote' | 'ul' | 'ol' | 'table' | 'hr' | 'code', selected = ''): string {
  const text = selected || ''
  switch (kind) {
    case 'h1':
      return `# ${text || '标题'}`
    case 'h2':
      return `## ${text || '小节'}`
    case 'bold':
      return `**${text || '加粗文字'}**`
    case 'quote':
      return `> ${text || '引用'}`
    case 'ul':
      return `- ${text || '列表项'}`
    case 'ol':
      return `1. ${text || '列表项'}`
    case 'code':
      return text.includes('\n') ? '```\n' + text + '\n```' : `\`${text || 'code'}\``
    case 'table':
      return '| 列一 | 列二 |\n| --- | --- |\n| 内容 | 内容 |'
    case 'hr':
      return '\n---\n'
    default:
      return text
  }
}
