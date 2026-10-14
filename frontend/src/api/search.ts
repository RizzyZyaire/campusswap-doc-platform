// 全文检索与异步重建接口（课件 10.1 检索；课件 13.1 索引重建走 RabbitMQ 异步）。
import { get, post } from './request'
import type {
  DocumentSearchEsDtoReq,
  SearchEngineStatusVo,
  SearchRebuildTask,
  SearchResultVo
} from '@/types/search'

/**
 * 全文检索（GET /api/search/documents，权限 doc:search）。
 *
 * @param params 关键词 / 检索范围 / 分类 / 时间范围 / 排序 / 分页
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
 * 提交全量重建索引任务（POST /api/search/rebuild，权限 doc:manage）。
 *
 * 课件 13.1：接口只负责"下单"——落任务、发 RabbitMQ 消息、立即返回 SUBMITTED + taskId，
 * 真正的重建由后台消费者执行，前端轮询 {@link getRebuildTask} 看进度。
 *
 * @returns 任务（status=SUBMITTED；消息队列不可用时为 FAILED 并带原因）
 */
export function submitRebuild(): Promise<SearchRebuildTask> {
  return post<SearchRebuildTask>('/search/rebuild')
}

/**
 * 查询重建任务状态（GET /api/search/rebuild/{taskId}，权限 doc:manage）。
 *
 * @param taskId 任务 ID（提交时返回）
 * @returns 任务当前状态与结果
 */
export function getRebuildTask(taskId: string): Promise<SearchRebuildTask> {
  return get<SearchRebuildTask>(`/search/rebuild/${taskId}`)
}

/**
 * 最近的重建任务（GET /api/search/rebuild/recent，权限 doc:manage）。
 *
 * @returns 任务列表（新→旧）
 */
export function getRecentRebuildTasks(): Promise<SearchRebuildTask[]> {
  return get<SearchRebuildTask[]>('/search/rebuild/recent')
}