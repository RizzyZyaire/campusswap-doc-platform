// 文档域接口（API_SPECIFICATION §4.6：检索 / 我的 / 治理 / 回收站 / 详情 / 增改删 / 状态流转 / 版本 / 收藏）。
import { del, get, post, put } from './request'
import type {
  DocumentAuditDtoReq,
  DocumentCreateDtoReq,
  DocumentDeriveDtoReq,
  DocumentDestroyDtoReq,
  DocumentDetailVo,
  DocumentManageDtoReq,
  DocumentMineDtoReq,
  DocumentRejectDtoReq,
  DocumentRemarkDtoReq,
  DocumentSearchDtoReq,
  DocumentTrashDtoReq,
  DocumentUpdateDtoReq,
  DocumentVersionVo,
  DocumentVo,
  FavoritePageDtoReq,
  FavoriteVo,
  PageVo
} from '@/types'

/** 检索文档（GET /api/documents；只有 PUBLISHED；关键词 ≥2 字走全文检索）。 */
export function searchDocuments(params: DocumentSearchDtoReq): Promise<PageVo<DocumentVo>> {
  return get<PageVo<DocumentVo>>('/documents', { ...params })
}

/** 我的文档（GET /api/documents/mine）。 */
export function myDocuments(params: DocumentMineDtoReq): Promise<PageVo<DocumentVo>> {
  return get<PageVo<DocumentVo>>('/documents/mine', { ...params })
}

/** 全状态治理列表（GET /api/documents/manage，权限 doc:manage，含回收站行）。 */
export function manageDocuments(params: DocumentManageDtoReq): Promise<PageVo<DocumentVo>> {
  return get<PageVo<DocumentVo>>('/documents/manage', { ...params })
}

/** 回收站（GET /api/documents/trash）。 */
export function trashDocuments(params: DocumentTrashDtoReq): Promise<PageVo<DocumentVo>> {
  return get<PageVo<DocumentVo>>('/documents/trash', { ...params })
}

/** 我的收藏（GET /api/favorites）。 */
export function favoriteDocuments(params: FavoritePageDtoReq): Promise<PageVo<DocumentVo>> {
  return get<PageVo<DocumentVo>>('/favorites', { ...params })
}

/** 文档详情（GET /api/documents/{id}）。 */
export function documentDetail(id: string): Promise<DocumentDetailVo> {
  return get<DocumentDetailVo>(`/documents/${id}`)
}

/** 新建草稿（POST /api/documents）。 */
export function createDocument(body: DocumentCreateDtoReq): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>('/documents', body)
}

/**
 * 编辑文档（PUT /api/documents/{id}）。
 *
 * <p>`versionNum` 必填：带"打开编辑页时读到的那一版"。库中版本已变时后端返回 **409**
 * 「该文档已被他人修改（当前版本 vN），请刷新后重试」，前端按 §8.5 的版本冲突流程处理。</p>
 */
export function updateDocument(id: string, body: DocumentUpdateDtoReq): Promise<DocumentDetailVo> {
  return put<DocumentDetailVo>(`/documents/${id}`, body)
}

/** 提交发布（POST /api/documents/{id}/publish）。 */
export function publishDocument(id: string): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/publish`)
}

/** 派生新草稿（POST /api/documents/{id}/derive）。 */
export function deriveDocument(id: string, body: DocumentDeriveDtoReq): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/derive`, body)
}

/** 移入回收站（DELETE /api/documents/{id}）。 */
export function moveToTrash(id: string): Promise<void> {
  return del<void>(`/documents/${id}`)
}

/** 从回收站恢复（POST /api/documents/{id}/restore）。 */
export function restoreDocument(id: string): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/restore`)
}

/** 彻底删除（DELETE /api/documents/{id}/destroy，需 confirm=true）。 */
export function destroyDocument(id: string, body: DocumentDestroyDtoReq): Promise<void> {
  return del<void>(`/documents/${id}/destroy`, body)
}

/** 版本历史（GET /api/documents/{id}/versions）。 */
export function documentVersions(id: string, params: { pageNum?: number; pageSize?: number }): Promise<PageVo<DocumentVersionVo>> {
  return get<PageVo<DocumentVersionVo>>(`/documents/${id}/versions`, { ...params })
}

/** 收藏（POST /api/documents/{id}/favorite，幂等）。 */
export function favorite(id: string): Promise<FavoriteVo> {
  return post<FavoriteVo>(`/documents/${id}/favorite`)
}

/** 取消收藏（DELETE /api/documents/{id}/favorite，幂等）。 */
export function unfavorite(id: string): Promise<FavoriteVo> {
  return del<FavoriteVo>(`/documents/${id}/favorite`)
}

/** 审核通过（POST /api/documents/{id}/audit，权限 doc:audit）。 */
export function auditDocument(id: string, body: DocumentAuditDtoReq): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/audit`, body)
}

/** 驳回（POST /api/documents/{id}/reject，权限 doc:reject）。 */
export function rejectDocument(id: string, body: DocumentRejectDtoReq): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/reject`, body)
}

/** 归档（POST /api/documents/{id}/archive，权限 doc:archive）。 */
export function archiveDocument(id: string, body: DocumentRemarkDtoReq): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/archive`, body)
}

/** 恢复上架（POST /api/documents/{id}/republish，权限 doc:archive）。 */
export function republishDocument(id: string): Promise<DocumentDetailVo> {
  return post<DocumentDetailVo>(`/documents/${id}/republish`)
}

/**
 * 注意：后端**没有** `offline`（下架）端点 —— 权限点 `doc:offline` 是预留位，
 * 界面上不渲染任何入口（UI_UX_SPECIFICATION §10.6 / API_SPECIFICATION §9.4）。
 * 归档（ARCHIVED）已覆盖"下架但可检索只读"的语义，因此这里刻意不提供 offline 方法。
 */
