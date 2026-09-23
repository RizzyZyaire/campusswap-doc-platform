// 分类与标签（API_SPECIFICATION §4.8 / §4.9）。
import { del, get, post, put } from './request'
import type { CategoryCreateDtoReq, CategoryUpdateDtoReq, CategoryVo, PageVo, TagCreateDtoReq, TagPageDtoReq, TagVo } from '@/types'

/** 分类树（GET /api/categories/tree，一次查全）。 */
export function categoryTree(): Promise<CategoryVo[]> {
  return get<CategoryVo[]>('/categories/tree')
}

/** 新增分类（POST /api/categories）。 */
export function createCategory(body: CategoryCreateDtoReq): Promise<CategoryVo> {
  return post<CategoryVo>('/categories', body)
}

/** 编辑分类（PUT /api/categories/{id}）。 */
export function updateCategory(id: string, body: CategoryUpdateDtoReq): Promise<CategoryVo> {
  return put<CategoryVo>(`/categories/${id}`, body)
}

/** 删除分类（DELETE /api/categories/{id}；有子分类或文档时 409）。 */
export function deleteCategory(id: string): Promise<void> {
  return del<void>(`/categories/${id}`)
}

/** 标签列表（GET /api/tags，按被引用次数倒序）。 */
export function tagList(params: TagPageDtoReq): Promise<PageVo<TagVo>> {
  return get<PageVo<TagVo>>('/tags', { ...params })
}

/** 新增标签（POST /api/tags，重名 409）。 */
export function createTag(body: TagCreateDtoReq): Promise<TagVo> {
  return post<TagVo>('/tags', body)
}

/** 重命名标签（PUT /api/tags/{id}）。 */
export function updateTag(id: string, body: TagCreateDtoReq): Promise<TagVo> {
  return put<TagVo>(`/tags/${id}`, body)
}

/** 删除标签（DELETE /api/tags/{id}；同时清理文档关联）。 */
export function deleteTag(id: string): Promise<void> {
  return del<void>(`/tags/${id}`)
}
