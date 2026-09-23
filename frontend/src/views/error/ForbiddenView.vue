<script setup lang="ts">
import { computed } from 'vue'
import { RouterLink, useRoute } from 'vue-router'
import { PERM, PERM_LABEL } from '@/utils/perm'
import { useUserStore } from '@/stores/user'

/**
 * 403（§8.14）：已登录但权限不足。必须写明**缺哪个权限点**，并给三个出口。
 * 守卫会把缺的权限点放在 `?need=`，来源路径放在 `?from=`。
 */
const route = useRoute()
const user = useUserStore()

const need = computed(() => (typeof route.query.need === 'string' ? route.query.need : ''))
const needLabel = computed(() => (need.value ? PERM_LABEL[need.value] ?? need.value : ''))
const from = computed(() => (typeof route.query.from === 'string' ? route.query.from : ''))
</script>

<template>
  <div class="page-head">
    <div>
      <h1>你没有访问该页面的权限</h1>
      <div class="desc">
        当前登录身份为 <b>{{ user.info?.realName ?? '—' }}</b>
        （{{ user.roles.join(' / ') || '—' }} · {{ user.info?.deptName ?? '—' }}）。
      </div>
    </div>
  </div>

  <div class="card">
    <div class="card-bd">
      <div v-if="need" class="alert a-warn mb16">
        <span class="ico">!</span>
        <span>
          本页需要的权限点是 <span class="mono">{{ need }}</span>
          <template v-if="needLabel">（{{ needLabel }}）</template>，你的账号当前没有它。
          <template v-if="from">被拦截的地址：<span class="mono">{{ from }}</span></template>
        </span>
      </div>
      <div v-else class="alert a-warn mb16">
        <span class="ico">!</span><span>你的账号当前没有访问该页面的权限。</span>
      </div>

      <div class="xs t3 mb16">
        权限来自两个来源：① 角色（你在单位中的岗位对应角色）；② 部门继承（你所在单位绑定的角色）。
        需要额外权限时请联系系统管理员调整角色或单位绑定；授权变更后<strong>无需重新登录</strong>，下一次请求即按新权限生效。
      </div>

      <div class="row wrap">
        <RouterLink class="btn btn-primary" to="/workbench">返回工作台</RouterLink>
        <RouterLink v-if="user.hasPerm(PERM.docSearch)" class="btn" to="/docs">去检索文档</RouterLink>
        <RouterLink class="btn" to="/me">查看我的权限</RouterLink>
      </div>
    </div>
  </div>
</template>
