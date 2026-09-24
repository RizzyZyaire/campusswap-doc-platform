import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { THEME_KEY } from '@/api/request'
import { THEME_META, type ThemeMeta } from '@/styles/theme-meta'

/**
 * 主题定义（六套）。
 *
 * <p>id / 中文名 / 气质标签 / 五色色卡全部来自**生成物** `@/styles/theme-meta`（源头是预览稿的
 * `var THEMES` 数组），产品侧不再手抄一遍名字 —— 之前手抄的那一版就和预览稿对不上
 * （名字相同、气质描述各写各的），也会让顶栏色卡缺颜色。</p>
 */
export type ThemeDef = ThemeMeta

/** 六套主题（数组顺序即选择器里的排列顺序）。 */
export const THEMES: ThemeDef[] = THEME_META

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
