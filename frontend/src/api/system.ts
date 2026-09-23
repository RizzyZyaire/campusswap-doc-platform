// 系统域接口：用户 / 角色 / 权限 / 部门（API_SPECIFICATION §4.2~§4.5）。
import { del, get, post, put } from './request'
import type {
  DeptDtoReq,
  DeptRoleDtoReq,
  DeptRoleVo,
  DeptVo,
  PageVo,
  PermissionCreateDtoReq,
  PermissionUpdateDtoReq,
  PermissionVo,
  RoleDtoReq,
  RolePageDtoReq,
  RolePermissionDtoReq,
  RolePermissionVo,
  RoleVo,
  UserCreateDtoReq,
  UserPageDtoReq,
  UserPasswordDtoReq,
  UserStatusDtoReq,
  UserUpdateDtoReq,
  UserVo
} from '@/types'

/** 用户列表（GET /api/users，权限 sys:user）。 */
export function userList(params: UserPageDtoReq): Promise<PageVo<UserVo>> {
  return get<PageVo<UserVo>>('/users', { ...params })
}

/** 用户详情（GET /api/users/{id}，含角色集合）。 */
export function userDetail(id: string): Promise<UserVo> {
  return get<UserVo>(`/users/${id}`)
}

/** 新增用户（POST /api/users，权限 sys:user:add）。 */
export function createUser(body: UserCreateDtoReq): Promise<UserVo> {
  return post<UserVo>('/users', body)
}

/** 编辑用户（PUT /api/users/{id}；username / password 不可改）。 */
export function updateUser(id: string, body: UserUpdateDtoReq): Promise<UserVo> {
  return put<UserVo>(`/users/${id}`, body)
}

/** 停用 / 冻结 / 启用（PUT /api/users/{id}/status；非 ACTIVE 立即踢下线）。 */
export function updateUserStatus(id: string, body: UserStatusDtoReq): Promise<UserVo> {
  return put<UserVo>(`/users/${id}/status`, body)
}

/** 管理员重置密码（PUT /api/users/{id}/password；提交后该用户全部 token 失效）。 */
export function resetUserPassword(id: string, body: UserPasswordDtoReq): Promise<void> {
  return put<void>(`/users/${id}/password`, body)
}

/** 角色列表（GET /api/roles）。 */
export function roleList(params: RolePageDtoReq): Promise<PageVo<RoleVo>> {
  return get<PageVo<RoleVo>>('/roles', { ...params })
}

/** 新增角色（POST /api/roles）。 */
export function createRole(body: RoleDtoReq): Promise<RoleVo> {
  return post<RoleVo>('/roles', body)
}

/** 编辑角色（PUT /api/roles/{id}；内置角色编码不可改）。 */
export function updateRole(id: string, body: RoleDtoReq): Promise<RoleVo> {
  return put<RoleVo>(`/roles/${id}`, body)
}

/** 删除角色（DELETE /api/roles/{id}；内置或仍被使用时 409）。 */
export function deleteRole(id: string): Promise<void> {
  return del<void>(`/roles/${id}`)
}

/** 角色已授权限（GET /api/roles/{id}/permissions）。 */
export function rolePermissions(id: string): Promise<RolePermissionVo> {
  return get<RolePermissionVo>(`/roles/${id}/permissions`)
}

/** 覆盖式保存角色权限（PUT /api/roles/{id}/permissions；保存后清缓存，授权即时生效）。 */
export function grantRolePermissions(id: string, body: RolePermissionDtoReq): Promise<void> {
  return put<void>(`/roles/${id}/permissions`, body)
}

/** 权限树（GET /api/permissions/tree）。 */
export function permissionTree(): Promise<PermissionVo[]> {
  return get<PermissionVo[]>('/permissions/tree')
}

/** 新增权限节点（POST /api/permissions）。 */
export function createPermission(body: PermissionCreateDtoReq): Promise<PermissionVo> {
  return post<PermissionVo>('/permissions', body)
}

/** 编辑权限节点（PUT /api/permissions/{id}；移到自身子孙下 400）。 */
export function updatePermission(id: string, body: PermissionUpdateDtoReq): Promise<PermissionVo> {
  return put<PermissionVo>(`/permissions/${id}`, body)
}

/** 删除权限节点（DELETE /api/permissions/{id}）。 */
export function deletePermission(id: string): Promise<void> {
  return del<void>(`/permissions/${id}`)
}

/** 部门树（GET /api/depts/tree）。 */
export function deptTree(): Promise<DeptVo[]> {
  return get<DeptVo[]>('/depts/tree')
}

/** 新增部门（POST /api/depts）。 */
export function createDept(body: DeptDtoReq): Promise<DeptVo> {
  return post<DeptVo>('/depts', body)
}

/** 编辑部门（PUT /api/depts/{id}）。 */
export function updateDept(id: string, body: DeptDtoReq): Promise<DeptVo> {
  return put<DeptVo>(`/depts/${id}`, body)
}

/** 删除部门（DELETE /api/depts/{id}；有子部门或成员时 409）。 */
export function deleteDept(id: string): Promise<void> {
  return del<void>(`/depts/${id}`)
}

/** 部门已绑角色（GET /api/depts/{id}/roles）。 */
export function deptRoles(id: string): Promise<DeptRoleVo> {
  return get<DeptRoleVo>(`/depts/${id}/roles`)
}

/** 覆盖式保存部门角色（PUT /api/depts/{id}/roles；变更后该部门用户权限即时生效）。 */
export function bindDeptRoles(id: string, body: DeptRoleDtoReq): Promise<void> {
  return put<void>(`/depts/${id}/roles`, body)
}
