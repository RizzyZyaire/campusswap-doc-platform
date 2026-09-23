// 系统域类型（GLOSSARY §3.7「系统域」逐字对齐）。
import type { AuditVo, DocumentStatus, PageDtoReq, PermType, UserStatus } from './api'

/** 登录入参。 */
export interface LoginDtoReq {
  username: string
  password: string
}

/** 登录用户信息（`/api/auth/login` 与 `/api/auth/me` 出参）。 */
export interface UserInfoVo extends AuditVo {
  id: string
  username: string
  realName: string
  deptId: string
  deptName: string | null
  roles: string[]
  avatarUrl: string | null
  permissions: string[]
}

/** 登录出参。 */
export interface LoginVo {
  token: string
  userInfo: UserInfoVo
  roles: string[]
  permissions: string[]
}

/** 用户列表项。 */
export interface UserVo extends AuditVo {
  id: string
  username: string
  realName: string
  deptId: string
  deptName: string | null
  roles: string[]
  phone: string | null
  email: string | null
  avatarUrl: string | null
  status: UserStatus
  lastLoginAt: string | null
}

/** 新增用户入参。 */
export interface UserCreateDtoReq {
  username: string
  realName: string
  deptId: string
  roles: string[]
  phone: string | null
  email: string | null
  password: string
}

/** 编辑用户入参（不含 username / password，见 AC-02 口径）。 */
export interface UserUpdateDtoReq {
  realName: string
  deptId: string
  roles: string[]
  phone: string | null
  email: string | null
}

/** 用户状态入参。 */
export interface UserStatusDtoReq {
  status: UserStatus
}

/** 用户分页查询（管理员重置密码用同一入参）。 */
export interface UserPageDtoReq extends PageDtoReq {
  status?: UserStatus
  deptId?: string
}

/** 用户密码入参（管理员重置密码）。 */
export interface UserPasswordDtoReq {
  newPassword: string
}

/** 自助改密入参（§4.1.4；登录即可、仅本人）。 */
export interface PasswordChangeDtoReq {
  oldPassword: string
  newPassword: string
}

/** 角色。 */
export interface RoleVo extends AuditVo {
  id: string
  name: string
  code: string
  description: string | null
  isBuiltin: number
  sortOrder: number
}

/** 角色新增 / 编辑入参（统一用同一个 DTO，见 API_SPEC §4.3）。 */
export interface RoleDtoReq {
  name: string
  code: string
  description: string | null
}

/** 角色分页查询（字段同 `PageDtoReq`，按 GLOSSARY 保留专名）。 */
export type RolePageDtoReq = PageDtoReq

/** 角色授权入参（覆盖式）。 */
export interface RolePermissionDtoReq {
  permissionIds: string[]
}

/** 角色已授权限出参。 */
export interface RolePermissionVo {
  roleId: string
  permissionIds: string[]
}

/** 权限节点（树形）。 */
export interface PermissionVo extends AuditVo {
  id: string
  name: string
  code: string
  type: PermType
  parentId: string
  ancestors: string
  path: string | null
  icon: string | null
  sortOrder: number
  children: PermissionVo[]
}

/** 权限节点新增入参。 */
export interface PermissionCreateDtoReq {
  name: string
  code: string
  type: PermType
  parentId: string
  sortOrder: number
  icon: string | null
  path: string | null
}

/** 权限节点编辑入参。 */
export interface PermissionUpdateDtoReq {
  name: string
  type: PermType
  parentId: string
  sortOrder: number
  icon: string | null
  path: string | null
}

/** 部门（树形）。 */
export interface DeptVo extends AuditVo {
  id: string
  name: string
  parentId: string
  ancestors: string
  sortOrder: number
  children: DeptVo[]
}

/** 部门新增 / 编辑入参。 */
export interface DeptDtoReq {
  name: string
  parentId: string
  sortOrder: number
}

/** 部门绑定角色入参（覆盖式）。 */
export interface DeptRoleDtoReq {
  roleIds: string[]
}

/** 部门已绑角色出参。 */
export interface DeptRoleVo {
  deptId: string
  roleIds: string[]
}

/** 用户角色回显用（列表批量补名）。 */
export interface UserRoleCodesVo {
  userId: string
  roles: string[]
}

/** 治理列表入参（全状态，权限 `doc:manage`）。 */
export interface DocumentManageDtoReq extends PageDtoReq {
  status?: DocumentStatus
  categoryId?: string
  authorId?: string
  startTime?: string
  endTime?: string
  sort?: string
}
