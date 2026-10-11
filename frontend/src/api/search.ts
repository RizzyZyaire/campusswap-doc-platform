// 全文检索接口（课件 10.1：ES + IK；ES 未连接时后端自动降级为 MySQL 引擎）。
import { get, post } from './request'
import type { DocumentSearchEsDtoReq, SearchEngineStatusVo, SearchRebuildVo, SearchResultVo } from '@/types/search'

/**
 * 全文检索（GET /api/search/documents，权限 doc:search）。
 *
 * @param params 关键词 / 检索范围 / 分类 / 排序 / 分页
 * @returns 带高亮的命中列表（含实际使用的引擎说明）
 */
export function searchFullText(params: DocumentSearchEsDtoReq): Promise<SearchResultVo> {
  return get<SearchResultVo>('/search/documents', { ...params })
}

/**
 * 当前检索引擎状态（GET /api/search/status，权限 doc:search）。
 *
 * @returns ES 是否可用 / 索引文档数 / 中文说明
 */
export function searchEngineStatus(): Promise<SearchEngineStatusVo> {
  return get<SearchEngineStatusVo>('/search/status')
}

/**
 * 全量重建检索索引（POST /api/search/rebuild，权限 doc:manage）。
 *
 * @returns 重建条数、耗时与失败原因
 */
export function rebuildSearchIndex(): Promise<SearchRebuildVo> {
  return post<SearchRebuildVo>('/search/rebuild')
}
