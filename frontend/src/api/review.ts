// 审核队列（API_SPECIFICATION §4.7；通过/驳回/归档/恢复上架在 documents.ts）。
import { get } from './request'
import type { DocumentVo, PageVo, ReviewPageDtoReq } from '@/types'

/**
 * 待审 / 已审文档队列（GET /api/review/documents，权限 `doc:review`）。
 *
 * <p>`status` 只接受 `PUBLISHED` / `ARCHIVED`，默认 `PUBLISHED`（待审队列）。</p>
 */
export function reviewDocuments(params: ReviewPageDtoReq): Promise<PageVo<DocumentVo>> {
  return get<PageVo<DocumentVo>>('/review/documents', { ...params })
}
