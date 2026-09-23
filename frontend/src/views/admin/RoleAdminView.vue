<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import Pager from '@/components/Pager.vue'
import { ApiError } from '@/api/request'
import * as sysApi from '@/api/system'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import type { PermissionVo, RoleDtoReq, RoleVo } from '@/types'

/**
 * 角色与权限（UI_UX_SPECIFICATION §8.11，权限 `sys:role`）。
 *
 * 左角色列表（内置角色不可删）+ 右三层权限树（父节点半选、全选 / 反选 / 保存授权）
 * +「权限点清单」tab（需 `sys:perm`，可就地编辑 / 删除权限节点）。
 *
 * 两处按后端契约做的事：
 *  ① 授权是**覆盖式**保存（`PUT /api/roles/{id}/permissions` 传全量 id 数组），所以勾选期间
 *     必须在本地维护完整集合，保存时一次性提交，不能"勾一个发一个"；
 *  ② 保存授权后后端会**清空该角色下所有用户的权限缓存**（BR-18），旧 token 下次请求即生效
 *     —— 这一点写在按钮旁边的说明里，因为它看着"没有重新登录却生效了"，不解释会像 bug。
 */
const ui = useUiStore()
const user = useUserStore()

const tab = ref<'roles' | 'perms'>('roles')

/* ---------------- 角色 ---------------- */

const roles = ref<RoleVo[]>([])
const roleTotal = ref(0)
const rolePage = ref(1)
const rolePageSize = ref(20)
const rolesLoading = ref(true)
const rolesError = ref('')
const noperm = ref(false)
const currentRole = ref<RoleVo | null>(null)

/* ---------------- 权限树 ---------------- */

const tree = ref<PermissionVo[]>([])
const treeLoading = ref(false)
const treeError = ref('')
/** 勾选中的权限 id 集合（本地态，保存时整体提交）。 */
const checked = ref<Set<string>>(new Set())
const saving = ref(false)
const granted = ref<Set<string>>(new Set())

const canGrant = computed(() => user.hasPerm(PERM.sysRoleGrant))
const canAddRole = computed(() => user.hasPerm(PERM.sysRoleAdd))
const canEditRole = computed(() => user.hasPerm(PERM.sysRoleEdit))
const canDeleteRole = computed(() => user.hasPerm(PERM.sysRoleDelete))
const canPerm = computed(() => user.hasPerm(PERM.sysPerm))
const canPermEdit = computed(() => user.hasPerm(PERM.sysPermEdit))
const canPermDelete = computed(() => user.hasPerm(PERM.sysPermDelete))

const dirty = computed(() => {
  if (checked.value.size !== granted.value.size) return true
  for (const id of checked.value) if (!granted.value.has(id)) return true
  return false
})

/** 拍平权限树（父 → 子顺序），用于全选 / 反选与计数。 */
const flatPerms = computed(() => {
  const out: PermissionVo[] = []
  const walk = (nodes: PermissionVo[]): void => {
    for (const n of nodes) {
      out.push(n)
      if (n.children?.length) walk(n.children)
    }
  }
  walk(tree.value)
  return out
})

/** 权限点清单分页（39 条按 10 条一页展示，纯前端分页，因为树接口一次给全）。 */
const permPage = ref(1)
const permPageSize = ref(10)
const permPageList = computed(() => flatPerms.value.slice((permPage.value - 1) * permPageSize.value, permPage.value * permPageSize.value))

async function loadRoles(): Promise<void> {
  rolesLoading.value = true
  rolesError.value = ''
  noperm.value = false
  try {
    const page = await sysApi.roleList({ pageNum: rolePage.value, pageSize: rolePageSize.value })
    roles.value = page.list
    roleTotal.value = page.total
    if (!currentRole.value && page.list.length) await selectRole(page.list[0])
  } catch (e) {
    if (e instanceof ApiError && e.isForbidden) {
      noperm.value = true
      rolesError.value = e.message
    } else {
      rolesError.value = e instanceof ApiError ? e.message : '加载失败'
    }
  } finally {
    rolesLoading.value = false
  }
}

async function loadTree(): Promise<void> {
  treeLoading.value = true
  treeError.value = ''
  try {
    tree.value = await sysApi.permissionTree()
  } catch (e) {
    treeError.value = e instanceof ApiError ? e.message : '权限树加载失败'
  } finally {
    treeLoading.value = false
  }
}

async function selectRole(role: RoleVo): Promise<void> {
  currentRole.value = role
  checked.value = new Set()
  granted.value = new Set()
  try {
    const vo = await sysApi.rolePermissions(role.id)
    checked.value = new Set(vo.permissionIds)
    granted.value = new Set(vo.permissionIds)
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '读取该角色权限失败')
  }
}

/** 节点状态：全选 / 半选 / 未选（父节点的半选由子孙推导出来）。 */
function nodeState(node: PermissionVo): 'on' | 'half' | '' {
  const all = collectIds(node)
  const hit = all.filter((id) => checked.value.has(id)).length
  if (hit === 0) return ''
  return hit === all.length ? 'on' : 'half'
}

function collectIds(node: PermissionVo): string[] {
  const out: string[] = [node.id]
  const walk = (nodes: PermissionVo[]): void => {
    for (const n of nodes) {
      out.push(n.id)
      if (n.children?.length) walk(n.children)
    }
  }
  if (node.children?.length) walk(node.children)
  return out
}

/** 勾选一个节点：连同其子孙一起勾 / 取消（父节点不自动向上勾，后端授权是按 id 集合存的）。 */
function toggle(node: PermissionVo): void {
  if (!canGrant.value) return
  const ids = collectIds(node)
  const next = new Set(checked.value)
  const on = nodeState(node) === 'on'
  for (const id of ids) {
    if (on) next.delete(id)
    else next.add(id)
  }
  checked.value = next
}

function selectAll(): void {
  if (!canGrant.value) return
  checked.value = new Set(flatPerms.value.map((p) => p.id))
}

function invert(): void {
  if (!canGrant.value) return
  const next = new Set<string>()
  for (const p of flatPerms.value) if (!checked.value.has(p.id)) next.add(p.id)
  checked.value = next
}

function resetChecked(): void {
  checked.value = new Set(granted.value)
}

async function saveGrant(): Promise<void> {
  const role = currentRole.value
  if (!role) return
  saving.value = true
  try {
    await sysApi.grantRolePermissions(role.id, { permissionIds: [...checked.value] })
    granted.value = new Set(checked.value)
    ui.ok('授权已保存，该角色下用户的权限缓存已清空（下次请求即生效）')
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '保存授权失败')
  } finally {
    saving.value = false
  }
}

/* ---------------- 角色增删改 ---------------- */

const roleFormOpen = ref(false)
const roleFormMode = ref<'create' | 'edit'>('create')
const roleFormError = ref('')
const roleForm = reactive({ name: '', code: '', description: '' })

function openCreateRole(): void {
  roleFormMode.value = 'create'
  roleForm.name = ''
  roleForm.code = ''
  roleForm.description = ''
  roleFormError.value = ''
  roleFormOpen.value = true
}

function openEditRole(role: RoleVo): void {
  roleFormMode.value = 'edit'
  roleForm.name = role.name
  roleForm.code = role.code
  roleForm.description = role.description ?? ''
  roleFormError.value = ''
  roleFormOpen.value = true
}

async function submitRole(): Promise<void> {
  roleFormError.value = ''
  const body: RoleDtoReq = {
    name: roleForm.name.trim(),
    code: roleForm.code.trim().toUpperCase(),
    description: roleForm.description.trim() || null
  }
  if (!body.name || !body.code) {
    roleFormError.value = '角色名称不能为空且不超过64个字符'
    return
  }
  try {
    if (roleFormMode.value === 'create') {
      const created = await sysApi.createRole(body)
      ui.ok('已新增角色')
      roleFormOpen.value = false
      await loadRoles()
      await selectRole(created)
    } else {
      const updated = await sysApi.updateRole(currentRole.value?.id ?? '', body)
      ui.ok('已保存角色')
      roleFormOpen.value = false
      await loadRoles()
      await selectRole(updated)
    }
  } catch (e) {
    roleFormError.value = e instanceof ApiError ? e.message : '保存失败'
  }
}

const roleDeleteOpen = ref(false)
const roleDeleteError = ref('')

async function submitDeleteRole(): Promise<void> {
  const role = currentRole.value
  if (!role) return
  roleDeleteError.value = ''
  try {
    await sysApi.deleteRole(role.id)
    roleDeleteOpen.value = false
    ui.ok('已删除角色')
    currentRole.value = null
    await loadRoles()
  } catch (e) {
    // 内置角色 / 仍被使用的角色都是 409，文案用服务端的
    roleDeleteError.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

/* ---------------- 权限点清单（增删改） ---------------- */

const permFormOpen = ref(false)
const permFormMode = ref<'create' | 'edit'>('create')
const permFormError = ref('')
const permForm = reactive({
  id: '',
  name: '',
  code: '',
  type: 'BUTTON' as 'DIR' | 'MENU' | 'BUTTON',
  parentId: '0',
  sortOrder: 0,
  icon: '',
  path: ''
})

function openCreatePerm(): void {
  permFormMode.value = 'create'
  permForm.id = ''
  permForm.name = ''
  permForm.code = ''
  permForm.type = 'BUTTON'
  permForm.parentId = '0'
  permForm.sortOrder = flatPerms.value.length
  permForm.icon = ''
  permForm.path = ''
  permFormError.value = ''
  permFormOpen.value = true
}

function openEditPerm(p: PermissionVo): void {
  permFormMode.value = 'edit'
  permForm.id = p.id
  permForm.name = p.name
  permForm.code = p.code
  permForm.type = p.type
  permForm.parentId = p.parentId
  permForm.sortOrder = p.sortOrder
  permForm.icon = p.icon ?? ''
  permForm.path = p.path ?? ''
  permFormError.value = ''
  permFormOpen.value = true
}

async function submitPerm(): Promise<void> {
  permFormError.value = ''
  if (!permForm.name.trim() || !permForm.code.trim()) {
    permFormError.value = '权限名称与编码都不能为空'
    return
  }
  try {
    if (permFormMode.value === 'create') {
      await sysApi.createPermission({
        name: permForm.name.trim(),
        code: permForm.code.trim(),
        type: permForm.type,
        parentId: permForm.parentId,
        sortOrder: permForm.sortOrder,
        icon: permForm.icon.trim() || null,
        path: permForm.path.trim() || null
      })
      ui.ok('已新增权限点')
    } else {
      await sysApi.updatePermission(permForm.id, {
        name: permForm.name.trim(),
        type: permForm.type,
        parentId: permForm.parentId,
        sortOrder: permForm.sortOrder,
        icon: permForm.icon.trim() || null,
        path: permForm.path.trim() || null
      })
      ui.ok('已保存权限点')
    }
    permFormOpen.value = false
    await loadTree()
  } catch (e) {
    permFormError.value = e instanceof ApiError ? e.message : '保存失败'
  }
}

const permDeleteTarget = ref<PermissionVo | null>(null)
const permDeleteError = ref('')

async function submitDeletePerm(): Promise<void> {
  const p = permDeleteTarget.value
  if (!p) return
  permDeleteError.value = ''
  try {
    await sysApi.deletePermission(p.id)
    permDeleteTarget.value = null
    ui.ok('已删除权限点')
    await loadTree()
    if (currentRole.value) await selectRole(currentRole.value)
  } catch (e) {
    permDeleteError.value = e instanceof ApiError ? e.message : '删除失败'
  }
}

function onRolePage(page: number): void {
  rolePage.value = page
  void loadRoles()
}

function onPermPage(page: number): void {
  permPage.value = page
}

onMounted(async () => {
  await Promise.all([loadTree(), loadRoles()])
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>角色与权限</h1>
        <div class="desc">
          角色是权限点的集合，用户通过「直接授予角色」或「所属单位继承角色」获得权限。
          授权**覆盖式保存**，保存后立即生效，无需重新登录。
        </div>
      </div>
      <div class="acts">
        <button class="btn" :class="{ 'btn-primary': tab === 'perms' }" @click="tab = tab === 'perms' ? 'roles' : 'perms'">
          {{ tab === 'perms' ? '返回角色' : '权限点清单' }}
        </button>
        <button v-if="canAddRole" class="btn btn-primary" @click="openCreateRole">新增角色</button>
      </div>
    </div>

    <StateBlock v-if="noperm" kind="noperm" :desc="rolesError">
      <RouterLink class="btn btn-sm" to="/workbench">回到工作台</RouterLink>
    </StateBlock>

    <!-- 权限点清单 tab（需 sys:perm） -->
    <template v-else-if="tab === 'perms'">
      <StateBlock v-if="!canPerm" kind="noperm" title="你没有查看权限点清单的权限" desc="该 tab 需要 sys:perm。" />
      <StateBlock v-else-if="treeLoading" kind="loading" :rows="6" />
      <StateBlock v-else-if="treeError" kind="error" :desc="treeError">
        <button class="btn btn-sm" @click="loadTree">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!flatPerms.length" kind="empty" title="权限数据未初始化" desc="请先执行 backend/sql/data.sql。" />
      <div v-else class="card">
        <div class="card-hd">
          <h3>权限点清单</h3>
          <span class="sub">
            {{ tree.length }} 个目录 · 共 {{ flatPerms.length }} 个权限点（目录 / 菜单 / 按钮三层）
          </span>
          <div class="acts">
            <button v-if="canPermEdit" class="btn btn-sm btn-primary" @click="openCreatePerm">新增权限点</button>
          </div>
        </div>
        <table class="tbl">
          <thead>
            <tr>
              <th>权限点</th>
              <th class="w-fix2">编码</th>
              <th class="w-fix">类型</th>
              <th class="w-fix2">父节点</th>
              <th class="w-fix">排序</th>
              <th class="w-fix2"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="p in permPageList" :key="p.id">
              <td class="sm">{{ p.name }}</td>
              <td class="mono">{{ p.code }}</td>
              <td><span class="badge plain">{{ p.type }}</span></td>
              <td class="mono">{{ p.parentId }}</td>
              <td class="sm t2">{{ p.sortOrder }}</td>
              <td class="acts">
                <button v-if="canPermEdit" class="link" @click="openEditPerm(p)">编辑</button>
                <button v-if="canPermDelete" class="link danger" @click="permDeleteTarget = p">删除</button>
                <span v-if="!canPermEdit && !canPermDelete" class="link muted">只读</span>
              </td>
            </tr>
          </tbody>
        </table>
        <Pager :page-num="permPage" :page-size="permPageSize" :total="flatPerms.length" @change="onPermPage" />
      </div>
    </template>

    <template v-else>
      <div class="grid g-list">
        <div class="card">
          <div class="card-hd">
            <h3>角色</h3>
            <span class="sub">共 {{ roleTotal }} 个</span>
          </div>
          <StateBlock v-if="rolesLoading" kind="loading" :rows="4" />
          <StateBlock v-else-if="rolesError" kind="error" :desc="rolesError" />
          <StateBlock v-else-if="!roles.length" kind="empty" title="暂无自定义角色" desc="内置角色随 data.sql 初始化。">
            <button v-if="canAddRole" class="btn btn-sm btn-primary" @click="openCreateRole">新增角色</button>
          </StateBlock>
          <div v-else class="card-bd tight tree">
            <div
              v-for="r in roles"
              :key="r.id"
              class="node lv1"
              :class="{ on: currentRole?.id === r.id }"
              @click="selectRole(r)"
            >
              <span class="strong">{{ r.name }}</span>
              <span class="cnt">{{ r.code }}</span>
              <span class="code">{{ r.isBuiltin ? '内置' : '自定义' }}</span>
            </div>
          </div>
          <div v-if="currentRole" class="card-bd">
            <div class="kv">
              <div class="k">名称</div><div>{{ currentRole.name }}</div>
              <div class="k">编码</div><div class="mono">{{ currentRole.code }}</div>
              <div class="k">说明</div><div>{{ currentRole.description ?? '—' }}</div>
              <div class="k">类型</div><div>{{ currentRole.isBuiltin ? '内置（不可删除）' : '自定义' }}</div>
            </div>
            <div class="row wrap mt12">
              <button v-if="canEditRole" class="btn btn-sm" @click="openEditRole(currentRole)">编辑角色</button>
              <button
                v-if="canDeleteRole && !currentRole.isBuiltin"
                class="btn btn-sm btn-danger"
                @click="roleDeleteOpen = true"
              >
                删除角色
              </button>
              <span v-if="!canEditRole && !canDeleteRole" class="xs t3">你没有角色维护权限（sys:role:edit / delete）</span>
            </div>
            <div class="alert a-info mt12">
              <span class="ico">i</span>
              <span>内置角色不可删除、编码不可修改；如需限制范围，请新建自定义角色。</span>
            </div>
          </div>
          <Pager :page-num="rolePage" :page-size="rolePageSize" :total="roleTotal" @change="onRolePage" />
        </div>

        <div class="card">
          <div class="card-hd">
            <h3>{{ currentRole ? `「${currentRole.name}」的权限` : '权限' }}</h3>
            <span class="sub">
              勾选即授权 · 共 {{ flatPerms.length }} 个权限点 · 已选 {{ checked.size }}
              <template v-if="dirty">· 未保存</template>
            </span>
            <div class="acts">
              <template v-if="canGrant">
                <button class="btn btn-sm" :disabled="saving" @click="selectAll">全选</button>
                <button class="btn btn-sm" :disabled="saving" @click="invert">反选</button>
                <button class="btn btn-sm" :disabled="saving || !dirty" @click="resetChecked">撤销改动</button>
                <button class="btn btn-primary btn-sm" :disabled="saving || !dirty" @click="saveGrant">
                  {{ saving ? '保存中…' : '保存授权' }}
                </button>
              </template>
              <span v-else class="link muted">只读（缺 sys:role:grant）</span>
            </div>
          </div>

          <StateBlock v-if="treeLoading" kind="loading" :rows="5" />
          <StateBlock v-else-if="treeError" kind="error" :desc="treeError">
            <button class="btn btn-sm" @click="loadTree">重新加载</button>
          </StateBlock>
          <StateBlock v-else-if="!tree.length" kind="empty" title="权限数据未初始化" desc="请先执行 backend/sql/data.sql。" />
          <div v-else class="card-bd tight tree" :class="{ 'tree-busy': saving }">
            <template v-for="dir in tree" :key="dir.id">
              <div class="node lv1" @click="toggle(dir)">
                <span class="checkbox" :class="nodeState(dir)"></span>
                <span class="strong">{{ dir.name }}</span>
                <span class="code">{{ dir.code }}</span>
              </div>
              <template v-for="menu in dir.children" :key="menu.id">
                <div class="node lv2" @click="toggle(menu)">
                  <span class="checkbox" :class="nodeState(menu)"></span>
                  <span>{{ menu.name }}</span>
                  <span class="code">{{ menu.code }}</span>
                </div>
                <div
                  v-for="btn in menu.children"
                  :key="btn.id"
                  class="node lv3"
                  @click="toggle(btn)"
                >
                  <span class="checkbox" :class="nodeState(btn)"></span>
                  <span class="sm">{{ btn.name }}</span>
                  <span class="code">{{ btn.code }}</span>
                </div>
              </template>
            </template>
          </div>

          <div v-if="canGrant" class="card-bd">
            <div class="alert a-info">
              <span class="ico">i</span>
              <span>
                保存是<b>覆盖式</b>的：提交当前勾选的全集。保存后后端会清空该角色下所有用户的权限缓存（BR-18），
                旧 token 下一次请求就按新权限判定 —— 所以"没重新登录也生效"是预期行为。
              </span>
            </div>
          </div>
          <div v-else class="card-bd tight">
            <div class="noperm">你没有授权权限（<span class="mono">sys:role:grant</span>），本页只读。</div>
          </div>
        </div>
      </div>
    </template>

    <!-- 角色新增 / 编辑 -->
    <div v-if="roleFormOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">{{ roleFormMode === 'create' ? '新增角色' : '编辑角色' }}</div>
        <div class="modal-bd">
          <div class="field mb12">
            <label class="label">角色名称 <span class="req">*</span></label>
            <input v-model="roleForm.name" class="input" placeholder="例如：院系文档管理员" />
          </div>
          <div class="field mb12">
            <label class="label">角色编码 <span class="req">*</span></label>
            <input
              v-model="roleForm.code"
              class="input"
              :disabled="roleFormMode === 'edit' && currentRole?.isBuiltin === 1"
              placeholder="大写字母、数字与下划线，如 COLLEGE_ADMIN"
            />
            <div class="hint">内置角色编码不可修改（后端会拒绝）</div>
          </div>
          <div class="field">
            <label class="label">说明</label>
            <textarea v-model="roleForm.description" class="textarea" rows="2" placeholder="这个角色用来做什么"></textarea>
          </div>
          <div v-if="roleFormError" class="alert a-danger mt12"><span class="ico">!</span><span>{{ roleFormError }}</span></div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="roleFormOpen = false">取消</button>
          <button class="btn btn-primary" @click="submitRole">保存</button>
        </div>
      </div>
    </div>

    <!-- 删除角色 -->
    <div v-if="roleDeleteOpen && currentRole" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">删除角色</div>
        <div class="modal-bd">
          <p>确定删除角色「{{ currentRole.name }}」？如果还有用户使用它，后端会返回 409 并拒绝。</p>
          <div v-if="roleDeleteError" class="alert a-danger mt12"><span class="ico">!</span><span>{{ roleDeleteError }}</span></div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="roleDeleteOpen = false">取消</button>
          <button class="btn btn-danger" @click="submitDeleteRole">确认删除</button>
        </div>
      </div>
    </div>

    <!-- 权限点新增 / 编辑 -->
    <div v-if="permFormOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">{{ permFormMode === 'create' ? '新增权限点' : '编辑权限点' }}</div>
        <div class="modal-bd">
          <div class="field mb12">
            <label class="label">权限名称 <span class="req">*</span></label>
            <input v-model="permForm.name" class="input" placeholder="例如：导出文档" />
          </div>
          <div class="field mb12">
            <label class="label">权限编码 <span class="req">*</span></label>
            <input
              v-model="permForm.code"
              class="input"
              :disabled="permFormMode === 'edit'"
              placeholder="小写字母、数字与冒号，如 doc:export"
            />
          </div>
          <div class="field mb12">
            <label class="label">类型</label>
            <select v-model="permForm.type" class="select">
              <option value="DIR">目录（DIR）</option>
              <option value="MENU">菜单（MENU）</option>
              <option value="BUTTON">按钮（BUTTON）</option>
            </select>
          </div>
          <div class="field mb12">
            <label class="label">父节点</label>
            <select v-model="permForm.parentId" class="select">
              <option value="0">顶级（无父节点）</option>
              <option v-for="p in flatPerms.filter((x) => x.type !== 'BUTTON')" :key="p.id" :value="p.id">
                {{ p.name }}（{{ p.code }}）
              </option>
            </select>
            <div class="hint">不能移动到自己的子孙节点下（后端会返回 400）</div>
          </div>
          <div class="field mb12">
            <label class="label">排序号</label>
            <input v-model.number="permForm.sortOrder" class="input" type="number" min="0" />
          </div>
          <div v-if="permForm.type !== 'BUTTON'" class="field">
            <label class="label">前端路由</label>
            <input v-model="permForm.path" class="input" placeholder="目录与菜单必填，如 /docs" />
          </div>
          <div v-if="permFormError" class="alert a-danger mt12"><span class="ico">!</span><span>{{ permFormError }}</span></div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="permFormOpen = false">取消</button>
          <button class="btn btn-primary" @click="submitPerm">保存</button>
        </div>
      </div>
    </div>

    <!-- 删除权限点 -->
    <div v-if="permDeleteTarget" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">删除权限点</div>
        <div class="modal-bd">
          <p>确定删除「{{ permDeleteTarget.name }}」（<span class="mono">{{ permDeleteTarget.code }}</span>）？所有角色的对应授权会一并清除。</p>
          <div v-if="permDeleteError" class="alert a-danger mt12"><span class="ico">!</span><span>{{ permDeleteError }}</span></div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="permDeleteTarget = null">取消</button>
          <button class="btn btn-danger" @click="submitDeletePerm">确认删除</button>
        </div>
      </div>
    </div>
  </div>
</template>
