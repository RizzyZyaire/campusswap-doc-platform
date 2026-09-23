// 公共包装类型（GLOSSARY §3.7「公共包装」）——字段名逐字对齐，禁止自造。
// 后端统一响应体 { code, message, data }；code 与 HTTP 状态码一致（200 成功）。

/** 统一响应体。 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

/** 分页出参。 */
export interface PageVo<T> {
  list: T[]
  total: number
  pageNum: number
  pageSize: number
}

/** 分页入参（各列表接口的专用入参都继承它）。 */
export interface PageDtoReq {
  pageNum?: number
  pageSize?: number
  keyword?: string
}

/** 所有实体型 VO 的基类（审计四列）。 */
export interface AuditVo {
  createdAt: string
  createdBy: string
  updatedAt: string
  updatedBy: string
}

/** 文档状态（PRD §4.2 状态机取值）。 */
export type DocumentStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED' | 'TRASH'

/** 用户状态。 */
export type UserStatus = 'ACTIVE' | 'LOCKED' | 'DISABLED'

/** 权限节点类型（目录 / 菜单 / 按钮）。 */
export type PermType = 'DIR' | 'MENU' | 'BUTTON'

/** 文档列表排序（GLOSSARY §3.6）。 */
export type DocumentSort = 'updatedAt_desc' | 'publishAt_desc' | 'viewCount_desc' | 'relevance'

/** 全文检索命中位置（仅检索分支返回）。 */
export type MatchedIn = 'title' | 'summary' | 'content'
