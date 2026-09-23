import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as authApi from '@/api/auth'
import { TOKEN_KEY } from '@/api/request'
import type { UserInfoVo } from '@/types'

/**
 * 用户 store：token + 登录用户信息（含角色与 39 个权限码）。
 *
 * <p>token 存 localStorage（键 `campusswap.token`，与 axios 拦截器同一常量）；
 * 刷新页面时 userInfo 为空 → 路由守卫会先 `fetchMe()` 再判定（UI_UX_SPECIFICATION §1.3）。</p>
 */
export const useUserStore = defineStore('user', () => {
  const token = ref<string | null>(localStorage.getItem(TOKEN_KEY))
  const info = ref<UserInfoVo | null>(null)
  /** 是否已尝试过 fetchMe（守卫用它避免重复请求）。 */
  const loaded = ref(false)

  const isLoggedIn = computed(() => Boolean(token.value))
  const permissions = computed<string[]>(() => info.value?.permissions ?? [])
  const roles = computed<string[]>(() => info.value?.roles ?? [])

  /**
   * 是否拥有某权限码。
   *
   * @param code 权限码（用 `PERM` 常量，别手写）
   */
  function hasPerm(code: string): boolean {
    return permissions.value.includes(code)
  }

  /** 任一权限命中。 */
  function hasAny(codes: string[]): boolean {
    return codes.some((c) => permissions.value.includes(c))
  }

  function setToken(value: string | null): void {
    token.value = value
    if (value) localStorage.setItem(TOKEN_KEY, value)
    else localStorage.removeItem(TOKEN_KEY)
  }

  /**
   * 登录并写入会话。
   *
   * @param username 工号 / 登录名
   * @param password 密码
   */
  async function login(username: string, password: string): Promise<void> {
    const vo = await authApi.login({ username, password })
    setToken(vo.token)
    info.value = vo.userInfo
    loaded.value = true
  }

  /** 拉取当前用户（刷新页面后的第一步）。 */
  async function fetchMe(): Promise<void> {
    if (!token.value) return
    info.value = await authApi.me()
    loaded.value = true
  }

  /** 清空本地会话（登出、401 都用它）。 */
  function clear(): void {
    setToken(null)
    info.value = null
    loaded.value = false
  }

  /** 登出：先通知后端删 token，无论成功与否都清本地。 */
  async function logout(): Promise<void> {
    try {
      await authApi.logout()
    } finally {
      clear()
    }
  }

  return { token, info, loaded, isLoggedIn, permissions, roles, hasPerm, hasAny, login, fetchMe, clear, logout, setToken }
})
