<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import logo from '@/assets/brand/logo.png'
import motto from '@/assets/brand/motto.png'
import ThemePicker from '@/components/ThemePicker.vue'
import { ApiError } from '@/api/request'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'

/**
 * 登录页（§8.1）：左侧校景（固定时光塔）+ 右侧登录卡。
 * 类名与结构与预览稿登录视图一一对应（.login-wrap / .login-aside(.bg+.veil+.inner+.foot) / .login-panel / .login-card），
 * 这样组件层 CSS 直接生效、视觉与预览稿一致。
 *
 * <p>2026-09-24 按用户反馈补三处：① 右上角加**主题选择器**（登录页没有顶栏，之前只能被动继承上次主题，
 * 想换也换不了）；② 密码行与登录按钮之间补间距；③ 右栏给主题色底 + 校徽 + 演示账号一键填充，
 * 不再是"纯白里孤零零一张表单"。</p>
 */
const route = useRoute()
const router = useRouter()
const user = useUserStore()
const ui = useUiStore()

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const errorText = ref('')

/** 演示账号（口令与 backend/sql/data.sql 一致；只在这台机器上用于自测，生产不会有这一块）。 */
const DEMO = [
  { label: '系统管理员', username: 'admin', password: 'Admin@123', hint: '全部 39 个权限' },
  { label: '文档管理员', username: 'docadmin', password: 'Doc@123456', hint: '审核 + 治理 + 分类标签' },
  { label: '普通员工', username: 'staff', password: 'Staff@123', hint: '写文档 / 检索 / 我的文档' }
]

/**
 * 一键填入演示账号。
 *
 * @param d 演示账号
 */
function fillDemo(d: (typeof DEMO)[number]): void {
  username.value = d.username
  password.value = d.password
  errorText.value = ''
}

async function onSubmit(): Promise<void> {
  errorText.value = ''
  if (!username.value.trim() || !password.value) {
    errorText.value = '请输入工号 / 登录名与密码'
    return
  }
  loading.value = true
  try {
    await user.login(username.value.trim(), password.value)
    ui.ok(`欢迎回来，${user.info?.realName ?? ''}`)
    const redirect = route.query.redirect
    await router.replace(typeof redirect === 'string' && redirect ? redirect : { name: 'workbench' })
  } catch (e) {
    // 文案口径：直接用服务端返回的中文（§I5），前端不重写
    errorText.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-wrap">
    <section class="login-aside">
      <div class="bg"></div>
      <div class="veil"></div>

      <div class="inner">
        <div class="crest">
          <img :src="logo" alt="河北师范大学校徽" />
          <div>
            <div class="tt">河北师范大学 · 校内文档平台</div>
            <div class="ss">HEBEI NORMAL UNIVERSITY</div>
          </div>
        </div>

        <img class="motto-img" :src="motto" alt="校训：怀天下 求真知" />

        <div>
          <div class="motto-sub">一九〇二 — 二〇二六 ｜ 怀天下，求真知</div>
          <ul>
            <li>统一身份认证登录，一个账号走遍全校文档</li>
            <li>按单位与分类归档公文、教务、科研与学生工作材料</li>
            <li>重制度版本留痕，审核与归档全程可查</li>
          </ul>
        </div>
      </div>

      <div class="foot">
        裕华校区 · 石家庄市南二环东路 20 号 ｜ 红旗校区 · 红旗大街 469 号<br />
        技术支持：信息化中心 · 0311-80787800
      </div>
    </section>

    <section class="login-panel">
      <!-- 登录页没有产品顶栏，主题选择器就放在这里（换主题按钮 + 6 张色卡） -->
      <div class="login-theme">
        <ThemePicker />
      </div>

      <div class="login-card">
        <div class="login-crest">
          <img :src="logo" alt="河北师范大学校徽" />
          <div>
            <div class="nm">统一身份认证</div>
            <div class="ss">河北师范大学 · 校内文档平台</div>
          </div>
        </div>

        <h1>登录</h1>
        <div class="sub">请使用统一身份认证的工号与密码登录</div>

        <form @submit.prevent="onSubmit">
          <div class="field">
            <label class="label" for="lgUser">工号 / 登录名</label>
            <input id="lgUser" v-model="username" class="input" autocomplete="username" placeholder="如 2018013" />
          </div>

          <div class="field">
            <label class="label" for="lgPass">密码</label>
            <div class="row">
              <input
                id="lgPass"
                v-model="password"
                class="input grow"
                :type="showPassword ? 'text' : 'password'"
                autocomplete="current-password"
                placeholder="请输入密码"
              />
              <button class="btn btn-sm" type="button" @click="showPassword = !showPassword">
                {{ showPassword ? '隐藏' : '显示' }}
              </button>
            </div>
          </div>

          <div v-if="errorText" class="alert a-danger mb12">
            <span class="ico">!</span><span>{{ errorText }}</span>
          </div>

          <button class="btn btn-primary w-full login-submit" type="submit" :disabled="loading">
            {{ loading ? '登录中…' : '登录' }}
          </button>
        </form>

        <!-- 演示账号：一键填充，省得手打三套口令 -->
        <div class="login-demo">
          <div class="hd">演示账号（点一下自动填入）</div>
          <div class="list">
            <button v-for="d in DEMO" :key="d.username" class="demo-item" type="button" @click="fillDemo(d)">
              <b>{{ d.label }}</b>
              <span class="mono">{{ d.username }}</span>
              <span class="xs t3">{{ d.hint }}</span>
            </button>
          </div>
        </div>

        <div class="login-foot">
          状态提示：初始、登录中、工号或密码错误、账号已冻结、账号已停用。<br />
          登录失败时不区分「工号不存在」与「密码错误」，避免工号被枚举。
        </div>
      </div>
    </section>
  </div>
</template>
