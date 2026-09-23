// 认证与自助改密（API_SPECIFICATION §4.1：登录 1 / 登出 2 / 当前用户 3 / 改密 56）。
import { get, post, put } from './request'
import type { LoginDtoReq, LoginVo, PasswordChangeDtoReq, UserInfoVo } from '@/types'

/** 登录（POST /api/auth/login）。 */
export function login(body: LoginDtoReq): Promise<LoginVo> {
  return post<LoginVo>('/auth/login', body)
}

/** 登出（POST /api/auth/logout，重复调用幂等 200）。 */
export function logout(): Promise<void> {
  return post<void>('/auth/logout')
}

/** 当前登录用户（GET /api/auth/me，含角色与 39 个权限码）。 */
export function me(): Promise<UserInfoVo> {
  return get<UserInfoVo>('/auth/me')
}

/** 自助改密（PUT /api/auth/password，登录即可、仅本人；成功后全部会话失效）。 */
export function changePassword(body: PasswordChangeDtoReq): Promise<void> {
  return put<void>('/auth/password', body)
}
