<script setup lang="ts">
import { computed } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import AppShell from '@/layouts/AppShell.vue'
import ToastHost from '@/components/ToastHost.vue'

// 登录页与 404 用空白布局（无侧栏），其余页面走带外壳的产品布局（UI_UX_SPECIFICATION §1.1）。
const route = useRoute()
const blank = computed(() => route.meta.blank === true)
</script>

<template>
  <RouterView v-if="blank" />
  <AppShell v-else />
  <ToastHost />
</template>
