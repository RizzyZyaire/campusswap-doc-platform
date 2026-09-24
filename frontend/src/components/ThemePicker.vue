<script setup lang="ts">
import { ref } from 'vue'
import { useThemeStore } from '@/stores/theme'
import { useUiStore } from '@/stores/ui'

/**
 * 主题选择器（可视化色卡）—— 顶栏与登录页共用同一个组件。
 *
 * <p>结构照预览稿「通用：主题选择器（右上角，可视化色卡）」：按钮上是当前主题的**三色点 + 中文名 + ▾**，
 * 弹层是 6 张「迷你界面」色卡（各显示**它自己那套**配色 + 气质标签 + 五色点 + 当前项 ✓）。
 * 样式在 `components.css` 的 `.themepick/.theme-btn/.dots/.theme-pop/.theme-grid/.tp`，
 * 每张卡的颜色由 `theme.css` 里 `.t-<主题id>{--c1..--c5}` 提供（都由 `pnpm run sync-preview` 从预览稿生成）。</p>
 *
 * @param label 是否显示主题中文名（窄容器里可关掉，只留色点 + ▾）
 */
withDefaults(defineProps<{ label?: boolean }>(), { label: true })

const theme = useThemeStore()
const ui = useUiStore()
const open = ref(false)

function toggle(): void {
  open.value = !open.value
}

/** 选一套主题：换完顺手收起面板（与预览稿一致）。 */
function pick(id: string): void {
  theme.setTheme(id)
  open.value = false
  ui.ok(`已切换到「${theme.currentTheme.name}」`)
}
</script>

<template>
  <div class="themepick">
    <button class="btn theme-btn" title="切换主题（6 套，即时生效）" @click="toggle">
      <span class="dots" :class="`t-${theme.current}`"><i></i><i></i><i></i></span>
      <span v-if="label">{{ theme.currentTheme.name }}</span>
      <span class="t3">▾</span>
    </button>

    <div v-if="open" class="theme-pop">
      <h4>选择主题（{{ theme.themes.length }} 套 · 即时生效 · 记住本次选择）</h4>
      <div class="theme-grid">
        <div
          v-for="t in theme.themes"
          :key="t.id"
          class="tp"
          :class="[`t-${t.id}`, { on: t.id === theme.current }]"
          :title="t.desc"
          @click="pick(t.id)"
        >
          <div class="mini">
            <div class="sb"><i></i><i></i><i></i></div>
            <div class="ct"><i class="w70"></i><i></i><i class="w50"></i></div>
          </div>
          <div class="cap">
            <b>{{ t.name }}</b>
            <span class="xs t3">{{ t.tag }}</span>
            <span class="sw"><i></i><i></i><i></i><i></i><i></i></span>
            <span v-if="t.id === theme.current" class="ok">✓</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
