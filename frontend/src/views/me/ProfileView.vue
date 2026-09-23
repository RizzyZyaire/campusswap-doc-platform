<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ApiError } from '@/api/request'
import * as authApi from '@/api/auth'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM_LABEL } from '@/utils/perm'
import { formatDateTime } from '@/utils/format'

/**
 * 我的资料（§8.13）：账号信息 / 角色的两个来源 / 39 个权限点的自有情况 / 账号安全（自助改密）。
 *
 * 权限点清单的口径：`/api/auth/me` 返回的是**合并后**的权限码集合（直授权 ∪ 角色权限 ∪ 部门继承角色），
 * 所以这里展示的是"最终有效"的 39 项，而不是某一个来源的贡献。
 */
const router = useRouter()
const ui = useUiStore()
const user = useUserStore()

const allCodes = computed(() => Object.keys(PERM_LABEL))
const heldCount = computed(() => user.permissions.length)
const heldSet = computed(() => new Set(user.permissions))

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const submitting = ref(false)
const formError = ref('')

/** 与后端 PasswordChangeDtoReq 的校验一致：8–32 位且同时包含字母与数字。 */
const passwordRule = /^(?=.*[A-Za-z])(?=.*\d)\S{8,32}$/

async function onChangePassword(): Promise<void> {
  formError.value = ''
  if (!oldPassword.value || !newPassword.value) {
    formError.value = '请填写原密码与新密码'
    return
  }
  if (!passwordRule.test(newPassword.value)) {
    formError.value = '新密码需 8–32 位且同时包含字母与数字'
    return
  }
  if (newPassword.value !== confirmPassword.value) {
    formError.value = '两次输入的新密码不一致'
    return
  }
  submitting.value = true
  try {
    await authApi.changePassword({ oldPassword: oldPassword.value, newPassword: newPassword.value })
    ui.ok('密码已修改，请用新密码重新登录')
    // 口径：改密成功后**全部会话失效（含当前设备）**，因此这里清本地会话并回登录页
    user.clear()
    await router.replace({ name: 'login' })
  } catch (e) {
    formError.value = e instanceof ApiError ? e.message : '修改失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}

onMounted(async () => {
  // 刷新进入本页时保证拿到最新的权限集合（守卫通常已拉过，这里兜一层）
  if (!user.info) {
    try {
      await user.fetchMe()
    } catch {
      /* 401 由 request 层统一处理 */
    }
  }
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>我的资料</h1>
        <div class="desc">账号、单位与角色的来源，以及 39 个权限点里你实际持有哪些。</div>
      </div>
    </div>

    <div class="grid g-side">
      <div>
        <div class="card">
          <div class="card-hd"><h3>账号信息</h3></div>
          <div class="card-bd">
            <div class="kv">
              <div class="k">姓名</div><div>{{ user.info?.realName ?? '—' }}</div>
              <div class="k">工号 / 登录名</div><div class="mono">{{ user.info?.username ?? '—' }}</div>
              <div class="k">所属单位</div><div>{{ user.info?.deptName ?? '—' }}</div>
              <div class="k">角色</div><div>{{ user.roles.join(' / ') || '—' }}</div>
              <div class="k">建档时间</div><div>{{ formatDateTime(user.info?.createdAt) }}</div>
              <div class="k">最近更新</div><div>{{ formatDateTime(user.info?.updatedAt) }}</div>
            </div>
          </div>
        </div>

        <div class="card">
          <div class="card-hd">
            <h3>权限点（{{ heldCount }} / {{ allCodes.length }}）</h3>
            <span class="sub">合并结果：直授权 ∪ 角色权限 ∪ 部门继承角色</span>
          </div>
          <div class="card-bd">
            <div class="perm-grid">
              <div v-for="code in allCodes" :key="code" class="perm-cell" :class="{ on: heldSet.has(code) }">
                <span class="dot" :class="heldSet.has(code) ? 'primary' : 'muted'"></span>
                <span class="nm">{{ PERM_LABEL[code] }}</span>
                <span class="mono t3">{{ code }}</span>
              </div>
            </div>
            <div class="xs t3 mt12">
              权限变更（角色授权 / 单位绑角色）后<strong>无需重新登录</strong>：后端会在事务提交后清掉你的权限缓存，
              下一次请求即按新权限判定。
            </div>
          </div>
        </div>
      </div>

      <div>
        <div class="card">
          <div class="card-hd"><h3>角色的两个来源</h3></div>
          <div class="card-bd sm t2">
            <p class="mb8"><b>① 角色直授</b>：管理员在「用户管理」里给你挂的角色（`sys_user_role`）。</p>
            <p class="mb8"><b>② 部门继承</b>：你所在单位绑定的角色，单位成员自动继承（`sys_dept_role`）。</p>
            <p>最终生效的是两者并集，再叠加「用户直授权限」的个别补权。本页顶部的 {{ heldCount }} 项即为并集结果。</p>
          </div>
        </div>

        <div class="card">
          <div class="card-hd"><h3>账号安全</h3><span class="sub">自助改密</span></div>
          <div class="card-bd">
            <div class="field">
              <label class="label" for="pwOld">原密码</label>
              <input id="pwOld" v-model="oldPassword" class="input" type="password" autocomplete="current-password" />
            </div>
            <div class="field">
              <label class="label" for="pwNew">新密码</label>
              <input id="pwNew" v-model="newPassword" class="input" type="password" autocomplete="new-password" />
              <div class="hint">8–32 位，且同时包含字母与数字</div>
            </div>
            <div class="field">
              <label class="label" for="pwNew2">确认新密码</label>
              <input id="pwNew2" v-model="confirmPassword" class="input" type="password" autocomplete="new-password" />
            </div>

            <div v-if="formError" class="alert a-danger mb12"><span class="ico">!</span><span>{{ formError }}</span></div>

            <button class="btn btn-primary w-full" :disabled="submitting" @click="onChangePassword">
              {{ submitting ? '提交中…' : '修改密码' }}
            </button>
            <div class="xs t3 mt12">
              修改成功后<strong>全部会话立即失效（含当前设备）</strong>，需要用新密码重新登录 —— 与管理员重置密码同一条安全口径。
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
