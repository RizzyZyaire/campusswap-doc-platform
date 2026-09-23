import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { THEME_KEY } from '@/api/request'

/** 主题定义（六套，名称与 id 与预览稿/规格一致；配色由 theme.css 的 CSS 变量给出）。 */
export interface ThemeDef {
  id: string
  name: string
  /** 一句话气质描述（主题选择器里显示）。 */
  desc: string
}

/** 六套主题（顺序即选择器里的排列顺序）。 */
export const THEMES: ThemeDef[] = [
  { id: 'hebtu', name: '师大蓝', desc: '校徽蓝，正式稳重' },
  { id: 'gingko', name: '银杏暖', desc: '暖金调，亲和' },
  { id: 'celadon', name: '青瓷绿', desc: '青瓷釉色，清透' },
  { id: 'ink', name: '墨玉青', desc: '墨绿沉静，耐看' },
  { id: 'jiang', name: '师大绛', desc: '绛红，典礼感' },
  { id: 'night', name: '墨夜黑', desc: '深色模式' }
]

const DEFAULT_THEME = 'hebtu'

/** 主题 store：写 `html[data-theme]`（theme.css 里的变量按它生效）并持久化到 localStorage。 */
export const useThemeStore = defineStore('theme', () => {
  const stored = localStorage.getItem(THEME_KEY)
  const current = ref<string>(THEMES.some((t) => t.id === stored) ? (stored as string) : DEFAULT_THEME)

  const currentTheme = computed<ThemeDef>(() => THEMES.find((t) => t.id === current.value) ?? THEMES[0])

  /** 应用到 <html data-theme>。 */
  function apply(): void {
    document.documentElement.dataset.theme = current.value
  }

  /**
   * 切换主题。
   *
   * @param id 主题 id
   */
  function setTheme(id: string): void {
    if (!THEMES.some((t) => t.id === id)) return
    current.value = id
    localStorage.setItem(THEME_KEY, id)
    apply()
  }

  return { current, currentTheme, themes: THEMES, setTheme, apply }
})
