<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import Pager from '@/components/Pager.vue'
import { ApiError } from '@/api/request'
import * as sysApi from '@/api/system'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { USER_STATUS_TEXT, relativeTime } from '@/utils/format'
import type { DeptVo, RoleVo, UserCreateDtoReq, UserStatus, UserUpdateDtoReq, UserVo } from '@/types'

/**
 * 用户管理（UI_UX_SPECIFICATION §8.10，权限 `sys:user`）。
 *
 * 筛选 + 用户表 + 新增/编辑弹窗 + 状态切换 + 重置密码。
 *
 * 三处按后端契约做的事：
 *  ① 编辑用户**不提交** `username` / `password`：接口对这两个字段挂了 `@Null`，带上就是 400
 *     「登录名与密码不可通过本接口修改」，所以编辑弹窗里工号只读、密码走「重置密码」；
 *  ② 状态改成非 `ACTIVE` 会**立即踢下线**（后端清 token 与权限缓存），确认框里必须写清楚；
 *  ③ 角色筛选：列表接口的入参只有 `keyword` / `deptId` / `status`（GLOSSARY §3.7），
 *     没有 `roles` 参数，所以不提供"按角色筛选"，避免做出一个点了没反应的控件。
 */
const ui = useUiStore()
const user = useUserStore()

const filters = reactive<{ keyword: string; deptId: string; status: '' | UserStatus }>({
  keyword: '',
  deptId: '',
  status: ''
})
const list = ref<UserVo[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(true)
const errorText = ref('')
const noperm = ref(false)

const depts = ref<DeptVo[]>([])
const roles = ref<RoleVo[]>([])

const canAdd = computed(() => user.hasPerm(PERM.sysUserAdd))
const canEdit = computed(() => user.hasPerm(PERM.sysUserEdit))
const canDisable = computed(() => user.hasPerm(PERM.sysUserDisable))
const canReset = computed(() => user.hasPerm(PERM.sysUserReset))

/** 单位下拉（拍平部门树，缩进表示层级）。 */
const deptOptions = computed(() => {
  const out: { id: string; label: string }[] = []
  const walk = (nodes: DeptVo[], depth: number): void => {
    for (const n of nodes) {
      out.push({ id: n.id, label: '\u3000'.repeat(depth) + n.name })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(depts.value, 0)
  return out
})

const deptName = (id: string): string => deptOptions.value.find((d) => d.id === id)?.label.trim() ?? id
const roleName = (code: string): string => roles.value.find((r) => r.code === code)?.name ?? code

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  noperm.value = false
  try {
    const page = await sysApi.userList({
      keyword: filters.keyword.trim() || undefined,
      deptId: filters.deptId || undefined,
      status: filters.status || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = page.list
    total.value = page.total
  } catch (e) {
    if (e instanceof ApiError && e.isForbidden) {
      noperm.value = true
      errorText.value = e.message
    } else {
      errorText.value = e instanceof ApiError ? e.message : '加载失败'
    }
  } finally {
    loading.value = false
  }
}

async function loadRefs(): Promise<void> {
  try {
    depts.value = await sysApi.deptTree()
  } catch {
    depts.value = []
  }
  try {
    const page = await sysApi.roleList({ pageNum: 1, pageSize: 100 })
    roles.value = page.list
  } catch {
    roles.value = []
  }
}

/* ---------------- 新增 / 编辑 ---------------- */

const formOpen = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editingId = ref('')
const formError = ref('')
const fieldErrors = reactive<Record<string, string>>({})
const form = reactive({
  username: '',
  realName: '',
  deptId: '',
  roles: [] as string[],
  phone: '',
  email: '',
  password: ''
})

function openCreate(): void {
  formMode.value = 'create'
  editingId.value = ''
  form.username = ''
  form.realName = ''
  form.deptId = deptOptions.value[0]?.id ?? ''
  form.roles = []
  form.phone = ''
  form.email = ''
  form.password = ''
  formError.value = ''
  clearFieldErrors()
  formOpen.value = true
}

function openEdit(row: UserVo): void {
  formMode.value = 'edit'
  editingId.value = row.id
  form.username = row.username
  form.realName = row.realName
  form.deptId = row.deptId
  form.roles = [...row.roles]
  form.phone = row.phone ?? ''
  form.email = row.email ?? ''
  form.password = ''
  formError.value = ''
  clearFieldErrors()
  formOpen.value = true
}

function clearFieldErrors(): void {
  for (const k of Object.keys(fieldErrors)) delete fieldErrors[k]
}

/** 按后端中文文案里的关键词把错误落到具体字段（400 字段级红字）。 */
function applyFieldError(message: string): void {
  const map: [RegExp, string][] = [
    [/登录名|用户名/, 'username'],
    [/姓名/, 'realName'],
    [/部门/, 'deptId'],
    [/角色/, 'roles'],
    [/手机号/, 'phone'],
    [/邮箱/, 'email'],
    [/密码/, 'password']
  ]
  for (const [re, field] of map) {
    if (re.test(message)) {
      fieldErrors[field] = message
      return
    }
  }
  formError.value = message
}

function toggleRole(code: string): void {
  const i = form.roles.indexOf(code)
  if (i >= 0) form.roles.splice(i, 1)
  else form.roles.push(code)
}

async function submitForm(): Promise<void> {
  clearFieldErrors()
  formError.value = ''
  if (!form.realName.trim() || !form.deptId || !form.roles.length) {
    if (!form.realName.trim()) fieldErrors.realName = '姓名不能为空且不超过64个字符'
    if (!form.deptId) fieldErrors.deptId = '部门不存在，请重新选择'
    if (!form.roles.length) fieldErrors.roles = '至少选择一个角色'
    return
  }
  try {
    if (formMode.value === 'create') {
      const body: UserCreateDtoReq = {
        username: form.username.trim(),
        realName: form.realName.trim(),
        deptId: form.deptId,
        roles: [...form.roles],
        phone: form.phone.trim() || null,
        email: form.email.trim() || null,
        password: form.password
      }
      await sysApi.createUser(body)
      ui.ok('已新增用户')
    } else {
      // 注意：编辑**不带** username / password（接口对它们挂了 @Null）
      const body: UserUpdateDtoReq = {
        realName: form.realName.trim(),
        deptId: form.deptId,
        roles: [...form.roles],
        phone: form.phone.trim() || null,
        email: form.email.trim() || null
      }
      await sysApi.updateUser(editingId.value, body)
      ui.ok('已保存用户信息')
    }
    formOpen.value = false
    await load()
  } catch (e) {
    if (e instanceof ApiError) applyFieldError(e.message)
    else formError.value = '保存失败，请稍后重试'
  }
}

/* ---------------- 状态切换 ---------------- */

const statusOpen = ref(false)
const statusTarget = ref<UserVo | null>(null)
const statusNext = ref<UserStatus>('ACTIVE')
const statusError = ref('')

const STATUS_OPTIONS: { value: UserStatus; label: string }[] = [
  { value: 'ACTIVE', label: '正常（ACTIVE）' },
  { value: 'LOCKED', label: '冻结（LOCKED）' },
  { value: 'DISABLED', label: '停用（DISABLED）' }
]

function openStatus(row: UserVo): void {
  statusTarget.value = row
  statusNext.value = row.status
  statusError.value = ''
  statusOpen.value = true
}

async function submitStatus(): Promise<void> {
  const target = statusTarget.value
  if (!target) return
  statusError.value = ''
  try {
    await sysApi.updateUserStatus(target.id, { status: statusNext.value })
    statusOpen.value = false
    ui.ok(statusNext.value === 'ACTIVE' ? '已启用（该用户可重新登录）' : '已改状态，该用户已立即下线')
    await load()
  } catch (e) {
    statusError.value = e instanceof ApiError ? e.message : '状态修改失败'
  }
}

/* ---------------- 重置密码 ---------------- */

const pwdOpen = ref(false)
const pwdTarget = ref<UserVo | null>(null)
const pwdValue = ref('')
const pwdError = ref('')

function openPwd(row: UserVo): void {
  pwdTarget.value = row
  pwdValue.value = ''
  pwdError.value = ''
  pwdOpen.value = true
}

async function submitPwd(): Promise<void> {
  const target = pwdTarget.value
  if (!target) return
  pwdError.value = ''
  if (!/^(?=.*[A-Za-z])(?=.*\d)\S{8,64}$/.test(pwdValue.value)) {
    pwdError.value = '新密码至少8位且需同时包含字母和数字'
    return
  }
  try {
    await sysApi.resetUserPassword(target.id, { newPassword: pwdValue.value })
    pwdOpen.value = false
    ui.ok('密码已重置，该用户的全部登录会话已失效')
  } catch (e) {
    pwdError.value = e instanceof ApiError ? e.message : '重置失败'
  }
}

function onFilter(): void {
  pageNum.value = 1
  void load()
}

function clearFilters(): void {
  filters.keyword = ''
  filters.deptId = ''
  filters.status = ''
  pageNum.value = 1
  void load()
}

function onPage(page: number): void {
  pageNum.value = page
  void load()
}

onMounted(() => {
  void loadRefs()
  void load()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>用户管理</h1>
        <div class="desc">
          账号开通与维护。状态改为冻结 / 停用会<b>立即强制下线</b>（清 token 与权限缓存）；
          重置密码会让该用户的所有会话失效。
        </div>
      </div>
      <div class="acts">
        <button v-if="canAdd" class="btn btn-primary" @click="openCreate">新增用户</button>
        <button class="btn" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>

    <StateBlock v-if="noperm" kind="noperm" :desc="errorText">
      <RouterLink class="btn btn-sm" to="/workbench">回到工作台</RouterLink>
    </StateBlock>

    <template v-else>
      <div class="card mb16">
        <div class="card-hd">
          <h3>筛选</h3>
          <span class="sub">后端列表接口只有 关键词 / 单位 / 状态 三个条件</span>
        </div>
        <div class="card-bd">
          <div class="filter">
            <div class="field">
              <label class="label">关键词</label>
              <input
                v-model="filters.keyword"
                class="input"
                placeholder="工号 / 姓名 / 手机号"
                @keyup.enter="onFilter"
              />
            </div>
            <div class="field">
              <label class="label">所属单位</label>
              <select v-model="filters.deptId" class="select" @change="onFilter">
                <option value="">全部</option>
                <option v-for="d in deptOptions" :key="d.id" :value="d.id">{{ d.label }}</option>
              </select>
            </div>
            <div class="field">
              <label class="label">状态</label>
              <select v-model="filters.status" class="select" @change="onFilter">
                <option value="">全部</option>
                <option v-for="s in STATUS_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </div>
            <div class="filter-acts">
              <button class="btn" @click="onFilter">查询</button>
              <button class="btn" @click="clearFilters">清空筛选</button>
            </div>
          </div>
          <div class="hint mt8">
            按角色筛选没有实现：`GET /api/users` 的入参（GLOSSARY §3.7 `UserPageDtoReq`）不含角色条件，
            与其做一个点了没反应的控件，不如在这里说明。
          </div>
        </div>
      </div>

      <StateBlock v-if="loading" kind="loading" :rows="5" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!list.length" kind="empty" title="没有匹配的用户" desc="换个筛选条件，或清空筛选看全部。">
        <button class="btn btn-sm" @click="clearFilters">清空筛选</button>
      </StateBlock>

      <div v-else class="card">
        <div class="card-hd"><h3>用户列表</h3><span class="sub">共 {{ total }} 人</span></div>
        <table class="tbl">
          <thead>
            <tr>
              <th class="w-fix2">工号</th>
              <th class="w-fix2">姓名</th>
              <th>所属单位</th>
              <th class="w-fix3">角色</th>
              <th class="w-fix">状态</th>
              <th class="w-fix2">最近登录</th>
              <th class="w-fix3"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="u in list" :key="u.id">
              <td class="mono">{{ u.username }}</td>
              <td>{{ u.realName }}</td>
              <td class="sm t2">{{ u.deptName ?? deptName(u.deptId) }}</td>
              <td>
                <span v-for="r in u.roles" :key="r" class="badge plain role-chip">{{ roleName(r) }}</span>
              </td>
              <td><StatusBadge :status="u.status" /></td>
              <td class="sm t2">{{ u.lastLoginAt ? relativeTime(u.lastLoginAt) : '从未登录' }}</td>
              <td class="acts">
                <button v-if="canEdit" class="link" @click="openEdit(u)">编辑</button>
                <button v-if="canDisable" class="link" @click="openStatus(u)">状态</button>
                <button v-if="canReset" class="link" @click="openPwd(u)">重置密码</button>
                <span v-if="!canEdit && !canDisable && !canReset" class="link muted">只读</span>
              </td>
            </tr>
          </tbody>
        </table>
        <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="onPage" />
      </div>
    </template>

    <!-- 新增 / 编辑 -->
    <div v-if="formOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">{{ formMode === 'create' ? '新增用户' : '编辑用户' }}</div>
        <div class="modal-bd">
          <div class="field mb12">
            <label class="label">工号（登录名）<span class="req">*</span></label>
            <input
              v-model="form.username"
              class="input"
              :disabled="formMode === 'edit'"
              placeholder="4~64 位字母、数字或下划线"
              :class="{ err: fieldErrors.username }"
            />
            <div class="hint">
              {{ formMode === 'edit' ? '工号不可修改（后端对 username 挂了 @Null）' : '登录用，创建后不可修改' }}
            </div>
            <div v-if="fieldErrors.username" class="hint err-text">{{ fieldErrors.username }}</div>
          </div>

          <div class="field mb12">
            <label class="label">姓名 <span class="req">*</span></label>
            <input v-model="form.realName" class="input" :class="{ err: fieldErrors.realName }" placeholder="真实姓名" />
            <div v-if="fieldErrors.realName" class="hint err-text">{{ fieldErrors.realName }}</div>
          </div>

          <div class="field mb12">
            <label class="label">所属单位 <span class="req">*</span></label>
            <select v-model="form.deptId" class="select" :class="{ err: fieldErrors.deptId }">
              <option value="">请选择</option>
              <option v-for="d in deptOptions" :key="d.id" :value="d.id">{{ d.label }}</option>
            </select>
            <div v-if="fieldErrors.deptId" class="hint err-text">{{ fieldErrors.deptId }}</div>
          </div>

          <div class="field mb12">
            <label class="label">角色 <span class="req">*</span></label>
            <div class="chip-pick">
              <span
                v-for="r in roles"
                :key="r.code"
                class="tag"
                :class="{ on: form.roles.includes(r.code) }"
                @click="toggleRole(r.code)"
              >
                {{ r.name }}
              </span>
            </div>
            <div class="hint">直接授予的角色；所属单位绑定的角色另外自动继承</div>
            <div v-if="fieldErrors.roles" class="hint err-text">{{ fieldErrors.roles }}</div>
          </div>

          <div class="field mb12">
            <label class="label">手机号</label>
            <input v-model="form.phone" class="input" :class="{ err: fieldErrors.phone }" placeholder="11 位手机号，可留空" />
            <div v-if="fieldErrors.phone" class="hint err-text">{{ fieldErrors.phone }}</div>
          </div>

          <div class="field mb12">
            <label class="label">邮箱</label>
            <input v-model="form.email" class="input" :class="{ err: fieldErrors.email }" placeholder="可留空" />
            <div v-if="fieldErrors.email" class="hint err-text">{{ fieldErrors.email }}</div>
          </div>

          <div v-if="formMode === 'create'" class="field">
            <label class="label">初始密码 <span class="req">*</span></label>
            <input v-model="form.password" class="input" :class="{ err: fieldErrors.password }" placeholder="至少 8 位，含字母和数字" />
            <div v-if="fieldErrors.password" class="hint err-text">{{ fieldErrors.password }}</div>
          </div>

          <div v-if="formError" class="alert a-danger mt12"><span class="ico">!</span><span>{{ formError }}</span></div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="formOpen = false">取消</button>
          <button class="btn btn-primary" @click="submitForm">保存</button>
        </div>
      </div>
    </div>

    <!-- 状态切换 -->
    <div v-if="statusOpen && statusTarget" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">修改账号状态</div>
        <div class="modal-bd">
          <p>{{ statusTarget.realName }}（{{ statusTarget.username }}）当前状态：{{ USER_STATUS_TEXT[statusTarget.status] ?? statusTarget.status }}</p>
          <div class="field mt12">
            <label class="label">改为</label>
            <select v-model="statusNext" class="select">
              <option v-for="s in STATUS_OPTIONS" :key="s.value" :value="s.value">{{ s.label }}</option>
            </select>
          </div>
          <div class="alert a-warn mt12">
            <span class="ico">!</span>
            <span>改成非「正常」会立即清除该用户的 token 与权限缓存，正在使用的会话会当场失效（F1-07）。</span>
          </div>
          <div v-if="statusError" class="hint err-text mt8">{{ statusError }}</div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="statusOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="statusNext === statusTarget.status" @click="submitStatus">确认</button>
        </div>
      </div>
    </div>

    <!-- 重置密码 -->
    <div v-if="pwdOpen && pwdTarget" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">重置密码</div>
        <div class="modal-bd">
          <p>为 {{ pwdTarget.realName }}（{{ pwdTarget.username }}）设置新密码。这是<b>管理员重置</b>，与该用户在自己「我的资料」里的自助改密是两条路径。</p>
          <div class="field mt12">
            <label class="label">新密码 <span class="req">*</span></label>
            <input v-model="pwdValue" class="input" placeholder="8~64 位，须同时含字母和数字" />
            <div class="hint">提交后该用户全部登录状态立即失效，需要用新密码重新登录</div>
            <div v-if="pwdError" class="hint err-text">{{ pwdError }}</div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="pwdOpen = false">取消</button>
          <button class="btn btn-primary" @click="submitPwd">确认重置</button>
        </div>
      </div>
    </div>
  </div>
</template>
