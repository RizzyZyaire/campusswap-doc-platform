// 操作日志接口（M14，权限 sys:center —— 只有系统管理员能看审计日志）。
import { get } from './request'
import type { OperationLogFiltersVo, OperationLogPageDtoReq, OperationLogVo } from '@/types/log'
import type { PageVo } from '@/types'

/**
 * 分页查询操作日志（GET /api/logs）。
 *
 * 组合查询条件：账号（模糊）+ 模块 + 动作 + 成功与否 + 时间范围。
 *
 * @param params 查询条件与分页
 * @returns 分页结果（时间倒序）
 */
export function pageOperationLogs(params: OperationLogPageDtoReq): Promise<PageVo<OperationLogVo>> {
  return get<PageVo<OperationLogVo>>('/logs', { ...params })
}

/**
 * 筛选下拉数据（GET /api/logs/filters）：库里已出现过的模块与动作。
 *
 * @returns 模块与动作清单
 */
export function operationLogFilters(): Promise<OperationLogFiltersVo> {
  return get<OperationLogFiltersVo>('/logs/filters')
}
