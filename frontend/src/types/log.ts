// 操作日志域类型（M14，对应后端 com.campusswap.system.vo.OperationLogVo 与 OperationLogPageDtoReq）。
// 字段真源：GLOSSARY §3.10。

/** 操作日志查询入参（GET /api/logs）。 */
export interface OperationLogPageDtoReq {
  /** 操作人账号（模糊匹配，可空） */
  username?: string
  /** 模块（精确匹配，如"文档"，可空） */
  module?: string
  /** 动作（精确匹配，如"发布"，可空） */
  action?: string
  /** 是否成功：1 成功 / 0 失败 / 不传为全部 */
  success?: number
  /** 起始时间（yyyy-MM-dd HH:mm:ss，可空） */
  startTime?: string
  /** 结束时间（可空） */
  endTime?: string
  pageNum?: number
  pageSize?: number
}

/** 一条操作日志。 */
export interface OperationLogVo {
  /** 日志 ID */
  id: string
  /** 操作人 ID（未登录场景为空） */
  userId: string | null
  /** 操作人账号 */
  username: string | null
  /** 模块 */
  module: string
  /** 动作 */
  action: string
  /** HTTP 方法 */
  method: string | null
  /** 请求路径 */
  uri: string | null
  /** 目标对象 ID */
  targetId: string | null
  /** 中文摘要 */
  summary: string | null
  /** 入参摘要（已脱敏） */
  params: string | null
  /** 客户端 IP */
  ip: string | null
  /** 是否成功 */
  success: boolean
  /** 失败原因 */
  errorMsg: string | null
  /** 耗时（毫秒） */
  costMs: number | null
  /** 记录时间 */
  createdAt: string | null
}

/** 筛选下拉数据（GET /api/logs/filters）。 */
export interface OperationLogFiltersVo {
  modules: string[]
  actions: string[]
}
