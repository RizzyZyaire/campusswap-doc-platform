<script setup lang="ts">
import { useUiStore } from '@/stores/ui'

/** 轻提示宿主（右下角堆叠，3 秒自动消失；样式沿用预览稿的 .toast 系列）。 */
const ui = useUiStore()

const kindClass: Record<string, string> = {
  ok: 'toast ok',
  warn: 'toast warn',
  err: 'toast err',
  info: 'toast'
}
</script>

<template>
  <div class="toast-host">
    <button
      v-for="t in ui.toasts"
      :key="t.id"
      :class="kindClass[t.kind]"
      @click="ui.dismiss(t.id)"
    >
      {{ t.text }}
    </button>
  </div>
</template>
