// 展示格式化：日期、金额（整数分）、状态与枚举文案。
// 口径：后端已经按 `yyyy-MM-dd HH:mm:ss` 返回字符串（ARCHITECTURE 统一日期序列化），前端只做裁剪与本地化。
import type { DocumentStatus, MatchedIn, UserStatus } from '@/types'

/** `2026-09-23 21:10:05` → `2026-09-23 21:10`。 */
export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  return value.length >= 16 ? value.slice(0, 16) : value
}

/** `2026-09-23 21:10:05` → `2026-09-23`。 */
export function formatDate(value: string | null | undefined): string {
  if (!value) return '—'
  return value.slice(0, 10)
}

/**
 * 相对时间（通知与列表用）：今天 → `今天 HH:mm`；昨天 → `昨天 HH:mm`；更早 → `MM-dd HH:mm`。
 *
 * @param value 后端时间字符串
 */
export function relativeTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value.replace(' ', 'T'))
  if (Number.isNaN(date.getTime())) return formatDateTime(value)
  const now = new Date()
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const diffDays = Math.floor((startOfToday - new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()) / 86400000)
  const hhmm = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  if (diffDays <= 0) return `今天 ${hhmm}`
  if (diffDays === 1) return `昨天 ${hhmm}`
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${hhmm}`
}

/** 补零。 */
function pad(n: number): string {
  return n < 10 ? `0${n}` : String(n)
}

/** 金额：整数分 → `¥12.34`（老师红线 R2：金额一律用整数分传输）。 */
export function formatPrice(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return '—'
  return `¥${(cents / 100).toFixed(2)}`
}

/** 文档状态中文。 */
export const DOCUMENT_STATUS_TEXT: Record<DocumentStatus, string> = {
  DRAFT: '草稿',
  PUBLISHED: '已发布',
  ARCHIVED: '已归档',
  TRASH: '回收站'
}

/** 用户状态中文。 */
export const USER_STATUS_TEXT: Record<UserStatus, string> = {
  ACTIVE: '正常',
  LOCKED: '已冻结',
  DISABLED: '已停用'
}

/** 命中位置中文（检索结果里标注"命中标题/摘要/正文"）。 */
export const MATCHED_IN_TEXT: Record<MatchedIn, string> = {
  title: '标题',
  summary: '摘要',
  content: '正文'
}

/** 版本变更类型中文（doc_version.change_type）。 */
export const CHANGE_TYPE_TEXT: Record<string, string> = {
  CREATE: '新建',
  EDIT: '编辑',
  PUBLISH: '发布',
  AUDIT: '审核通过',
  REJECT: '驳回',
  ARCHIVE: '归档',
  REPUBLISH: '恢复上架',
  DELETE: '移入回收站',
  RESTORE: '恢复'
}

/** 空值归一（表格里 null 显示为 —）。 */
export function orDash(value: string | number | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}
