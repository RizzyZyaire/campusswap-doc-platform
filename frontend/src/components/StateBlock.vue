<script setup lang="ts">
/**
 * 四态占位块（空 / 加载 / 错误 / 无权限）—— 每页四态统一走它，保证文案与视觉一致。
 * 用法：<StateBlock kind="loading" /><StateBlock kind="empty" title="…" desc="…"><button>…</button></StateBlock>
 */
withDefaults(
  defineProps<{
    /** 状态类型。 */
    kind: 'loading' | 'empty' | 'error' | 'noperm'
    /** 主标题（不传则用默认文案）。 */
    title?: string
    /** 补充说明。 */
    desc?: string
    /** 加载态的骨架行数。 */
    rows?: number
  }>(),
  { title: '', desc: '', rows: 5 }
)

const kindText: Record<string, { mark: string; title: string }> = {
  loading: { mark: '◌', title: '加载中…' },
  empty: { mark: '▤', title: '暂无数据' },
  error: { mark: '!', title: '加载失败' },
  noperm: { mark: '⛔', title: '你没有查看该内容的权限' }
}
</script>

<template>
  <div v-if="kind === 'loading'" class="sk">
    <div v-for="i in rows" :key="i" class="sk-line"></div>
  </div>
  <div v-else class="empty">
    <div class="mark">{{ kindText[kind].mark }}</div>
    <h4>{{ title || kindText[kind].title }}</h4>
    <p v-if="desc">{{ desc }}</p>
    <div class="mt12"><slot /></div>
  </div>
</template>
