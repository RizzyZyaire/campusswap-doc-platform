<script setup lang="ts">
import type { DocumentStatus, UserStatus } from '@/types'
import { DOCUMENT_STATUS_TEXT, USER_STATUS_TEXT } from '@/utils/format'

/**
 * 状态徽章（文档四态 / 用户三态共用）。
 * 类名规则：`badge` + 状态小写 —— 预览稿组件层里就是 `.badge.draft/.published/.archived/.trash/
 * .active/.locked/.disabled`，所以这里直接由状态推导，不另建映射表。
 */
const props = defineProps<{
  status: DocumentStatus | UserStatus | string
}>()

const cls = `badge ${String(props.status).toLowerCase()}`
const text =
  props.status in DOCUMENT_STATUS_TEXT
    ? DOCUMENT_STATUS_TEXT[props.status as DocumentStatus]
    : USER_STATUS_TEXT[props.status as UserStatus] ?? props.status
</script>

<template>
  <span :class="cls">{{ text }}</span>
</template>
