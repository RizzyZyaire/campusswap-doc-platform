import { ref } from 'vue'
import { defineStore } from 'pinia'

/** 提示条类型：ok 成功 / warn 注意 / err 失败 / info 一般信息。 */
export type ToastKind = 'ok' | 'warn' | 'err' | 'info'

/** 一条提示。 */
export interface ToastItem {
  id: number
  kind: ToastKind
  text: string
}

let seq = 0

/**
 * 全局提示（轻提示，右下角 3 秒自动消失）。
 *
 * <p>口径：文案优先用服务端返回的 `message`（UI_UX_SPECIFICATION I5），前端只决定"用哪种样式"。</p>
 */
export const useUiStore = defineStore('ui', () => {
  const toasts = ref<ToastItem[]>([])
  /** 侧栏在窄屏下折叠（< 1100px 时由布局自动设置）。 */
  const sidebarCollapsed = ref(false)

  /**
   * 弹一条提示。
   *
   * @param text 文案（一般直接传服务端 message）
   * @param kind 类型
   * @param duration 停留毫秒
   */
  function toast(text: string, kind: ToastKind = 'info', duration = 3200): void {
    const id = ++seq
    toasts.value.push({ id, kind, text })
    window.setTimeout(() => {
      toasts.value = toasts.value.filter((t) => t.id !== id)
    }, duration)
  }

  /** 成功提示。 */
  function ok(text: string): void {
    toast(text, 'ok')
  }

  /** 失败提示。 */
  function err(text: string): void {
    toast(text, 'err', 4200)
  }

  /** 注意提示（如版本冲突）。 */
  function warn(text: string): void {
    toast(text, 'warn', 4200)
  }

  /** 关掉一条。 */
  function dismiss(id: number): void {
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  return { toasts, sidebarCollapsed, toast, ok, err, warn, dismiss }
})
