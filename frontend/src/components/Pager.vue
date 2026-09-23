<script setup lang="ts">
/** 分页条（与预览稿 .pager 一致；页码直接由组件计算结果）。 */
const props = defineProps<{
  pageNum: number
  pageSize: number
  total: number
}>()
const emit = defineEmits<{ (e: 'change', page: number): void }>()

const totalPages = () => Math.max(1, Math.ceil(props.total / props.pageSize))

function go(page: number): void {
  const max = totalPages()
  const next = Math.min(Math.max(1, page), max)
  if (next !== props.pageNum) emit('change', next)
}
</script>

<template>
  <div class="pager">
    <span>共 {{ total }} 条 · 第 {{ pageNum }} / {{ totalPages() }} 页</span>
    <div class="spacer"></div>
    <button class="pg" :disabled="pageNum <= 1" @click="go(pageNum - 1)">上一页</button>
    <button class="pg" :disabled="pageNum >= totalPages()" @click="go(pageNum + 1)">下一页</button>
  </div>
</template>
