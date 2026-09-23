// 文档域类型（GLOSSARY §3.7「文档域」逐字对齐）。
import type { AuditVo, DocumentSort, DocumentStatus, MatchedIn, PageDtoReq } from './api'

/** 文档列表项（不含正文）。 */
export interface DocumentVo extends AuditVo {
  id: string
  title: string
  summary: string | null
  categoryId: string
  categoryName: string | null
  authorId: string
  authorName: string | null
  status: DocumentStatus
  versionNum: number
  priceCents: number
  viewCount: number
  favoriteCount: number
  canEdit: boolean
  /** 命中片段（命中词以 <em> 包裹）；仅关键词检索时非空。 */
  highlight?: string | null
  /** 命中位置；仅关键词检索时非空。 */
  matchedIn?: MatchedIn | null
}

/** 标签。 */
export interface TagVo extends AuditVo {
  id: string
  name: string
  useCount: number
}

/** 文档详情。 */
export interface DocumentDetailVo extends DocumentVo {
  contentMd: string | null
  derivedFromId: string | null
  rejectReason: string | null
  favorited: boolean
  tags: TagVo[]
}

/** 新建文档入参。 */
export interface DocumentCreateDtoReq {
  title: string
  summary: string | null
  contentMd: string | null
  categoryId: string | null
  tagIds: string[]
  priceCents: number
}

/**
 * 编辑文档入参：比新建多 `id` 与 `versionNum` 两个**必填**字段。
 * `versionNum` 带"打开编辑页时读到的那一版"，与库中不一致服务端返回 409（陈旧表单防覆盖，
 * 见 ARCHITECTURE §17 ADR-07 与 API_SPECIFICATION §9.5）。
 */
export interface DocumentUpdateDtoReq extends DocumentCreateDtoReq {
  id: string
  versionNum: number
}

/** 检索入参（≥2 字走全文检索，可命中正文）。 */
export interface DocumentSearchDtoReq extends PageDtoReq {
  categoryId?: string
  tagIds?: string[]
  status?: DocumentStatus
  sort?: DocumentSort
}

/** 我的文档入参。 */
export interface DocumentMineDtoReq extends PageDtoReq {
  status?: DocumentStatus
}

/** 回收站入参（字段与 `PageDtoReq` 相同，按 GLOSSARY 保留专名便于对照接口）。 */
export type DocumentTrashDtoReq = PageDtoReq

/** 收藏列表入参。 */
export type FavoritePageDtoReq = PageDtoReq

/** 审核队列入参。 */
export interface ReviewPageDtoReq extends PageDtoReq {
  status?: DocumentStatus
}

/** 标签分页入参。 */
export type TagPageDtoReq = PageDtoReq

/** 审核通过入参。 */
export interface DocumentAuditDtoReq {
  remark: string
}

/** 驳回入参。 */
export interface DocumentRejectDtoReq {
  reason: string
}

/** 归档 / 下架 / 恢复上架等的备注入参。 */
export interface DocumentRemarkDtoReq {
  remark: string
}

/** 派生新草稿入参（title 不传 = 原标题（副本））。 */
export interface DocumentDeriveDtoReq {
  title: string | null
}

/** 彻底删除入参（confirm 必须为 true，BR-08 二次确认）。 */
export interface DocumentDestroyDtoReq {
  confirm: boolean
}

/** 版本留痕。 */
export interface DocumentVersionVo extends AuditVo {
  id: string
  documentId: string
  versionNum: number
  title: string
  contentMd: string | null
  changeType: string
  changeRemark: string | null
  operatorId: string
  operatorName: string | null
}

/** 分类（树形）。 */
export interface CategoryVo extends AuditVo {
  id: string
  name: string
  parentId: string
  ancestors: string
  sortOrder: number
  children: CategoryVo[]
}

/** 分类新增入参。 */
export interface CategoryCreateDtoReq {
  name: string
  parentId: string
  sortOrder: number
}

/** 分类编辑入参。 */
export interface CategoryUpdateDtoReq {
  name: string
  parentId: string
  sortOrder: number
}

/** 标签新增 / 编辑入参。 */
export interface TagCreateDtoReq {
  name: string
}

/** 图片上传出参。 */
export interface ImageVo {
  url: string
}

/** 统计概览。 */
export interface StatVo {
  myDocumentCount: number
  myFavoriteCount: number
  publishedCount: number
}

/** 收藏操作出参。 */
export interface FavoriteVo {
  documentId: string
  favorited: boolean
  favoriteCount: number
}
