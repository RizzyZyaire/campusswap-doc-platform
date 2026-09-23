<script setup lang="ts">
import { RouterLink } from 'vue-router'
import logo from '@/assets/brand/logo.png'

/** 404（§8.14）：路由不存在（含手输地址）；空白布局，不显示侧栏。 */
</script>

<template>
  <div class="errpage">
    <img class="brand-logo" :src="logo" alt="河北师范大学校徽" />
    <div class="mark">404</div>
    <h4>页面不存在</h4>
    <p>地址可能输错了，或者这个页面已经合并到别处。</p>
    <div class="row wrap mt16">
      <RouterLink class="btn btn-primary" to="/workbench">返回工作台</RouterLink>
      <RouterLink class="btn" to="/docs">去检索文档</RouterLink>
    </div>
  </div>
</template>
