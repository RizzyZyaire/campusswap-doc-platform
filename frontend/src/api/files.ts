// 图片上传与统计（API_SPECIFICATION §4.10 / §4.11）。
import { get, upload } from './request'
import type { ImageVo, StatVo } from '@/types'

/**
 * 上传图片（POST /api/upload/image，权限 `doc:upload`）。
 *
 * <p>后端做三重白名单校验（扩展名 / MIME / 内容），失败返回 400 并带中文提示。</p>
 *
 * @param file       图片文件
 * @param onProgress 进度回调（0~100）
 */
export function uploadImage(file: File, onProgress?: (percent: number) => void): Promise<ImageVo> {
  return upload<ImageVo>('/upload/image', file, onProgress)
}

/** 统计概览（GET /api/stats/overview：我的文档数 / 我的收藏数 / 已发布数）。 */
export function statOverview(): Promise<StatVo> {
  return get<StatVo>('/stats/overview')
}
