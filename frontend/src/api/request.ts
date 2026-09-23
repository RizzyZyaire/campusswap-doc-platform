import axios, { type AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import type { ApiResult } from '@/types'

/** 本地保存 token 的键（唯一出处：拦截器与用户 store 都引这里）。 */
export const TOKEN_KEY = 'campusswap.token'

/** 主题偏好键（写在 localStorage，刷新后保持）。 */
export const THEME_KEY = 'campusswap.theme'

/**
 * 业务异常：统一携带后端返回的 `code` 与**中文文案**。
 *
 * <p>口径（UI_UX_SPECIFICATION I5）：文案一律用服务端返回的 `message`，前端不重写；
 * 视图只按 `code` 决定"怎么呈现"（行内红字 / 轻提示 / 整页错误 / 只读切换）。</p>
 */
export class ApiError extends Error {
  readonly code: number
  readonly httpStatus: number

  constructor(code: number, message: string, httpStatus = code) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.httpStatus = httpStatus
  }

  /** 未登录或 token 失效。 */
  get isUnauthorized(): boolean {
    return this.code === 401
  }

  /** 已登录但无权限。 */
  get isForbidden(): boolean {
    return this.code === 403
  }

  /** 参数校验失败（展示行内红字）。 */
  get isBadRequest(): boolean {
    return this.code === 400
  }

  /**
   * 状态冲突（409）：既可能是"归档/回收站只读"，也可能是"版本冲突（陈旧表单）"。
   * 视图必须用 `message` 文案区分，不能一律当成同一种（见 UI_UX_SPECIFICATION §8.5）。
   */
  get isConflict(): boolean {
    return this.code === 409
  }
}

/** 去掉 undefined / null / 空串参数，避免把空值当筛选条件发出去。 */
export function cleanParams(params?: Record<string, unknown>): Record<string, unknown> | undefined {
  if (!params) return undefined
  const out: Record<string, unknown> = {}
  for (const [k, v] of Object.entries(params)) {
    if (v === undefined || v === null) continue
    if (typeof v === 'string' && v.trim() === '') continue
    if (Array.isArray(v) && v.length === 0) continue
    out[k] = v
  }
  return out
}

/** 未登录回调：清 token 并广播事件，由 main.ts 接到 router 上跳登录页（避免此处 import router 形成环）。 */
function handleUnauthorized(): void {
  localStorage.removeItem(TOKEN_KEY)
  window.dispatchEvent(new CustomEvent('app:unauthorized'))
}

const instance: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? '/api',
  timeout: 20000,
  headers: { 'Content-Type': 'application/json' }
})

instance.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY)
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

instance.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResult<unknown> | undefined
    if (body && typeof body.code === 'number') {
      if (body.code === 200) return body.data as never
      throw new ApiError(body.code, body.message, response.status)
    }
    return response.data as never
  },
  (error: AxiosError<ApiResult<unknown>>) => {
    const status = error.response?.status ?? 0
    const body = error.response?.data
    const fallback = status === 0 ? '网络异常：请确认后端服务已启动（端口 10087）' : `请求失败（HTTP ${status}）`
    const apiError = new ApiError((body?.code ?? status) || 500, body?.message || fallback, status)
    if (apiError.isUnauthorized) handleUnauthorized()
    return Promise.reject(apiError)
  }
)

/**
 * 说明：响应拦截器已经把统一响应体拆开、只把 `data` 交出来，所以这里拿到的是**负载本身**，
 * 而不是 AxiosResponse —— 泛型参数是"调用方声明的负载类型"，故用一次显式断言把它接上。
 */

/**
 * GET。
 *
 * @param url    路径（以 / 开头的完整接口路径）
 * @param params 查询参数（自动剔除空值）
 * @returns 后端 `data` 字段
 */
export async function get<T>(url: string, params?: Record<string, unknown>): Promise<T> {
  const res = await instance.get(url, { params: cleanParams(params) })
  return res as unknown as T
}

/**
 * POST。
 *
 * @param url  路径
 * @param body 请求体（无请求体的接口传 undefined）
 * @returns 后端 `data` 字段
 */
export async function post<T>(url: string, body?: unknown): Promise<T> {
  const res = await instance.post(url, body)
  return res as unknown as T
}

/**
 * PUT。
 *
 * @param url  路径
 * @param body 请求体
 * @returns 后端 `data` 字段
 */
export async function put<T>(url: string, body?: unknown): Promise<T> {
  const res = await instance.put(url, body)
  return res as unknown as T
}

/**
 * DELETE（部分接口需要请求体，如彻底删除的 confirm）。
 *
 * @param url  路径
 * @param body 请求体
 * @returns 后端 `data` 字段
 */
export async function del<T>(url: string, body?: unknown): Promise<T> {
  const config: AxiosRequestConfig = body === undefined ? {} : { data: body }
  const res = await instance.delete(url, config)
  return res as unknown as T
}

/**
 * 上传文件（multipart）。
 *
 * @param url       路径
 * @param file      文件
 * @param onProgress 进度回调（0~100）
 * @returns 后端 `data` 字段
 */
export async function upload<T>(url: string, file: File, onProgress?: (percent: number) => void): Promise<T> {
  const form = new FormData()
  form.append('file', file)
  const res = await instance.post(url, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    onUploadProgress: (event) => {
      if (!onProgress || !event.total) return
      onProgress(Math.round((event.loaded / event.total) * 100))
    }
  })
  return res as unknown as T
}

export { instance as http }
