// 全文检索域类型（课件 10.1 + 用户追加要求：检索范围可选 / 关键词高亮 / 双引擎降级）。
// 与后端 com.campusswap.document.vo.{SearchHitVo,SearchResultVo,SearchEngineStatusVo} 一一对应。

/** 检索范围：只查标题 / 只查正文 / 标题+摘要+正文。 */
export type SearchScope = 'TITLE' | 'CONTENT' | 'ALL'

/** 检索范围的中文名（与后端 SearchScope#label 一致）。 */
export const SEARCH_SCOPE_TEXT: Record<SearchScope, string> = {
  TITLE: '标题',
  CONTENT: '正文',
  ALL: '标题 + 摘要 + 正文'
}

/** 全文检索入参（GET /api/search/documents）。 */
export interface DocumentSearchEsDtoReq {
  /** 关键词（必填；后端空关键词返回空列表） */
  keyword: string
  /** 检索范围，缺省 ALL */
  scope?: SearchScope
  /** 分类 ID（数字字符串，可空） */
  categoryId?: string
  /** 排序：relevance（相关度，默认） / updatedAt_desc / publishAt_desc / viewCount_desc */
  sort?: string
  pageNum?: number
  pageSize?: number
}

/** 单条命中（titleHighlight / snippet 是"已转义 + 已插 <mark>"的 HTML 片段）。 */
export interface SearchHitVo {
  id: string
  title: string
  titleHighlight: string
  snippet: string
  categoryName: string | null
  authorName: string | null
  status: string | null
  publishAt: string | null
  /** 相关度分数（两位小数字符串，如 "4.83"）；MySQL 降级引擎下为 null */
  score: string | null
  /** 命中字段：title / summary / content */
  matchedFields: string[]
}

/** 全文检索出参。 */
export interface SearchResultVo {
  /** 实际使用的引擎：elasticsearch / mysql */
  engine: string
  /** 引擎说明（中文，界面直接显示） */
  engineMessage: string
  scope: SearchScope
  scopeLabel: string
  keyword: string
  list: SearchHitVo[]
  total: number
  pageNum: number
  pageSize: number
}

/** 检索引擎状态（GET /api/search/status）。 */
export interface SearchEngineStatusVo {
  elasticsearchAvailable: boolean
  engine: string
  message: string
  indexCount: number
  endpoint: string
  ikAnalyzerReady: boolean
}

/** 索引重建结果（POST /api/search/rebuild，权限 doc:manage）。 */
export interface SearchRebuildVo {
  success: boolean
  indexed: number
  indexCount: number
  elapsedMs: number
  message: string
}
