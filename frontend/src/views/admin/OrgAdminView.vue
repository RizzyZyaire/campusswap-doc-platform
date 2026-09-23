<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import Pager from '@/components/Pager.vue'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/request'
import * as sysApi from '@/api/system'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { formatDateTime } from '@/utils/format'
import type { DeptDtoReq, DeptVo, RoleVo, UserVo } from '@/types'

/**
 * 组织机构（UI_UX_SPECIFICATION §8.12）：左机构树 + 右单位详情（基本信息 / 绑定角色 / 成员名单）。
 *
 * <p>树、角色、成员是三个独立接口，所以各自持有加载态与错误文案 —— 任何一个慢或失败都不会把整页拖成白屏，
 * 也不会让"角色拉不到"看起来像"这个单位没有角色"。</p>
 *
 * <p>权限按后端 @RequiresPermission 的**实际挂点**判定，不按"看起来像"推断：</p>
 * <ul>
 *   <li>机构树 / 已绑角色：`sys:dept`（本页入口权限，路由守卫已拦一次）</li>
 *   <li>机构增删改：`sys:dept:add` / `sys:dept:edit` / `sys:dept:delete`</li>
 *   <li>绑角色：`sys:role:grant`；角色清单：`sys:role`；成员名单：`sys:user`</li>
 * </ul>
 * 后两组与机构权限点是分开的，因此这里各自判定 —— 否则会渲染出一个点了必然 403 的按钮。
 */

/** 树的一行（扁平化后渲染）。`.tree` 只定义了 lv1~lv3 三档缩进，用 depth 映射即可，不必写递归子组件。 */
interface FlatDeptNode {
  dept: DeptVo
  depth: number
  hasChildren: boolean
}

const ui = useUiStore()
const user = useUserStore()

/** 后端 pageSize 上限就是 100（PageDtoReq @Max(100)），角色总数是个位数，一次拉全。 */
const ROLE_PAGE_SIZE = 100

// —— 权限判定 ——
/** 机构管理总开关：机构的增删改都要求同时具备本页入口权限。 */
const canManageDept = computed(() => user.hasPerm(PERM.sysDept))
const canAddDept = computed(() => canManageDept.value && user.hasPerm(PERM.sysDeptAdd))
const canEditDept = computed(() => canManageDept.value && user.hasPerm(PERM.sysDeptEdit))
const canDeleteDept = computed(() => canManageDept.value && user.hasPerm(PERM.sysDeptDelete))
/** 绑角色（写）：缺它就只读展示已绑角色。 */
const canGrantRole = computed(() => user.hasPerm(PERM.sysRoleGrant))
/** 角色清单（读）：GET /api/roles 挂的是 sys:role，与 sys:role:grant 不是一个点。 */
const canReadRoles = computed(() => user.hasPerm(PERM.sysRole))
/** 成员名单（读）：GET /api/users 挂的是 sys:user。 */
const canReadUsers = computed(() => user.hasPerm(PERM.sysUser))

// —— 树 ——
const loading = ref(true)
const errorText = ref('')
const noperm = ref(false)
const tree = ref<DeptVo[]>([])
const selectedId = ref('')
/** 折叠的节点 id。用数组而不是 Set：模板里做 includes 判断更直观，也不依赖集合的响应式代理。 */
const collapsed = ref<string[]>([])

/** `children` 后端恒为数组（DeptVo 默认 new ArrayList）；兜一层是防止某个节点为空让整棵树渲染中断。 */
function childrenOf(dept: DeptVo): DeptVo[] {
  return Array.isArray(dept.children) ? dept.children : []
}

/**
 * 把树拍平成数组。
 *
 * @param nodes            待拍平的节点
 * @param respectCollapse  true = 折叠的子树不进结果（用于左侧展示）；false = 全量（用于「上级」下拉与祖先判断）
 * @param depth            当前层级
 * @param out              累积结果
 * @returns 拍平后的行
 */
function flatten(nodes: DeptVo[], respectCollapse: boolean, depth = 0, out: FlatDeptNode[] = []): FlatDeptNode[] {
  for (const dept of nodes) {
    const kids = childrenOf(dept)
    out.push({ dept, depth, hasChildren: kids.length > 0 })
    if (kids.length > 0 && !(respectCollapse && collapsed.value.includes(dept.id))) {
      flatten(kids, respectCollapse, depth + 1, out)
    }
  }
  return out
}

/** 左侧可见行（受折叠影响）。 */
const visibleRows = computed(() => flatten(tree.value, true))
/** 全量行（不受折叠影响）：树头部的单位总数、「上级」下拉都用它。 */
const allRows = computed(() => flatten(tree.value, false))

/** `.tree` 只有 lv1~lv3 三档缩进，再深的层级一律按 lv3 处理，避免缩进把节点名挤没。 */
function levelClass(depth: number): string {
  if (depth <= 0) return 'lv1'
  if (depth === 1) return 'lv2'
  return 'lv3'
}

/** 下拉里的层级缩进：不能用内联样式（红线 R6），只能用全角空格凑。 */
function indent(depth: number): string {
  return '　'.repeat(depth)
}

function findDept(nodes: DeptVo[], id: string): DeptVo | null {
  for (const dept of nodes) {
    if (dept.id === id) return dept
    const hit = findDept(childrenOf(dept), id)
    if (hit) return hit
  }
  return null
}

const selectedDept = computed(() => findDept(tree.value, selectedId.value))

/** 上级名称由 parentId 反查；后端约定根部门的 parentId 为 "0"，顶级显示「—」。 */
const parentName = computed(() => {
  const parentId = selectedDept.value?.parentId ?? ''
  if (!parentId || parentId === '0') return '—'
  return findDept(tree.value, parentId)?.name ?? `#${parentId}`
})

function toggleCollapse(id: string): void {
  collapsed.value = collapsed.value.includes(id) ? collapsed.value.filter((x) => x !== id) : [...collapsed.value, id]
}

// —— 单位详情：绑定角色 ——
const allRoles = ref<RoleVo[]>([])
const boundRoleIds = ref<string[]>([])
/** 勾选草稿：点勾只改草稿，按「保存绑定」才提交，避免每点一下就发一次覆盖式请求。 */
const draftRoleIds = ref<string[]>([])
const roleLoading = ref(false)
const roleSaving = ref(false)
const roleError = ref('')

/** 有未保存的改动（覆盖式保存必须先让用户看清"这次会解绑谁"）。 */
const roleDirty = computed(() => {
  const draft = [...draftRoleIds.value].sort()
  const bound = [...boundRoleIds.value].sort()
  return draft.length !== bound.length || draft.some((id, i) => id !== bound[i])
})

/** 已绑角色的显示名：能读到角色清单就用「名称（编码）」，读不到就退回 ID —— 不假装知道名字。 */
const boundRoleLabels = computed<string[]>(() =>
  boundRoleIds.value.map((id) => {
    const hit = allRoles.value.find((r) => r.id === id)
    return hit ? `${hit.name}（${hit.code}）` : `#${id}`
  })
)

function toggleRole(roleId: string): void {
  draftRoleIds.value = draftRoleIds.value.includes(roleId)
    ? draftRoleIds.value.filter((id) => id !== roleId)
    : [...draftRoleIds.value, roleId]
}

/** 成员行上的角色：UserVo.roles 是**角色编码**集合，能读到角色清单时顺带换中文名。 */
function roleLabel(code: string): string {
  return allRoles.value.find((r) => r.code === code)?.name ?? code
}

// —— 单位详情：成员 ——
const members = ref<UserVo[]>([])
const memberTotal = ref(0)
const memberPage = ref(1)
const memberPageSize = ref(10)
const memberLoading = ref(false)
const memberError = ref('')
const memberDenied = ref(false)

// —— 新增 / 编辑弹窗 ——
const formOpen = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const formId = ref('')
const formName = ref('')
const formParentId = ref('0')
/** 排序号用字符串承接输入框，提交前再转数字：number 修饰符在清空输入框时会塞回空串，类型对不上。 */
const formSortOrder = ref('0')
const formBusy = ref(false)
/** 400 是参数级校验，按 request.ts 的口径落到字段旁的行内红字。 */
const formFieldError = ref('')
/** 其余错误（网络、403、500）用弹窗顶部横幅。 */
const formError = ref('')

/**
 * 「上级」候选：编辑时要排掉自身与自身子孙。
 * 后端也会拦（400「不能将部门移动到其子部门下」），前端先挡一次，省得用户白填一遍再被打回。
 */
const parentChoices = computed(() => {
  const forbidden: string[] = []
  if (formMode.value === 'edit' && formId.value) {
    const self = findDept(tree.value, formId.value)
    if (self) {
      const collect = (nodes: DeptVo[]): void => {
        for (const dept of nodes) {
          forbidden.push(dept.id)
          collect(childrenOf(dept))
        }
      }
      collect([self])
    }
  }
  return allRows.value.filter((row) => !forbidden.includes(row.dept.id))
})

// —— 删除二次确认 ——
const deleteTarget = ref<DeptVo | null>(null)
const deleteBusy = ref(false)
/** 失败时**原样**贴后端 message（如 409「该部门下仍有子部门或员工，请先转移」），前端不改写。 */
const deleteError = ref('')
/** 409 = 可纠正的业务冲突（还有子单位/成员），用警示色；其他错误用错误色。 */
const deleteConflict = ref(false)

// —— 加载 ——

/** 拉机构树（页面主加载）：树失败才是整页的错误/无权限态。 */
async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  noperm.value = false
  try {
    const nodes = await sysApi.deptTree()
    tree.value = nodes
    collapsed.value = []
    // 刷新后保持原选中（增删改之后不该丢掉用户的位置）；选中的单位没了就退回第一个根单位
    selectedId.value = selectedId.value && findDept(nodes, selectedId.value) ? selectedId.value : (nodes[0]?.id ?? '')
  } catch (e) {
    tree.value = []
    selectedId.value = ''
    if (e instanceof ApiError && e.isForbidden) noperm.value = true
    else errorText.value = e instanceof ApiError ? e.message : '加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
  await reloadDetail()
}

/** 重新拉选中单位的角色绑定与成员（首次进入、切换选中、增删改成功、手动重试都走这里）。 */
async function reloadDetail(): Promise<void> {
  const id = selectedId.value
  if (!id) {
    boundRoleIds.value = []
    draftRoleIds.value = []
    members.value = []
    memberTotal.value = 0
    return
  }
  await Promise.all([loadRoles(id), loadMembers(id)])
}

/** 拉已绑角色 + 角色清单。两者权限点不同，分开请求、分开降级。 */
async function loadRoles(deptId: string): Promise<void> {
  roleLoading.value = true
  roleError.value = ''
  allRoles.value = []
  try {
    const bound = await sysApi.deptRoles(deptId)
    boundRoleIds.value = bound.roleIds
    draftRoleIds.value = [...bound.roleIds]
  } catch (e) {
    boundRoleIds.value = []
    draftRoleIds.value = []
    roleError.value = e instanceof ApiError ? e.message : '角色绑定加载失败，请稍后重试'
  }
  // 角色清单挂的是 sys:role：没有它就不发这个注定 403 的请求，勾选区退化成"只显示已绑 ID"
  if (canReadRoles.value) {
    try {
      const page = await sysApi.roleList({ pageNum: 1, pageSize: ROLE_PAGE_SIZE })
      allRoles.value = page.list
    } catch (e) {
      roleError.value = e instanceof ApiError ? e.message : '角色清单加载失败，请稍后重试'
    }
  }
  roleLoading.value = false
}

/** 拉单位成员（可按状态/关键字扩展，这里只需要按单位筛选）。 */
async function loadMembers(deptId: string): Promise<void> {
  if (!canReadUsers.value || !deptId) {
    // 没有 sys:user 就不发注定 403 的请求，直接说明为什么看不到名单
    members.value = []
    memberTotal.value = 0
    memberDenied.value = true
    return
  }
  memberDenied.value = false
  memberLoading.value = true
  memberError.value = ''
  try {
    const page = await sysApi.userList({ deptId, pageNum: memberPage.value, pageSize: memberPageSize.value })
    members.value = page.list
    memberTotal.value = page.total
  } catch (e) {
    members.value = []
    memberTotal.value = 0
    if (e instanceof ApiError && e.isForbidden) memberDenied.value = true
    else memberError.value = e instanceof ApiError ? e.message : '成员加载失败，请稍后重试'
  } finally {
    memberLoading.value = false
  }
}

/** 切换选中单位。 */
function selectDept(id: string): void {
  if (selectedId.value === id) return
  selectedId.value = id
  // 换单位必须回第一页：否则可能停在新单位的空页上，看起来像"这个单位没人"
  memberPage.value = 1
  void reloadDetail()
}

function changeMemberPage(page: number): void {
  memberPage.value = page
  void loadMembers(selectedId.value)
}

/** 覆盖式保存单位角色；后端在事务提交后清该单位用户的权限缓存，成员下次请求即生效。 */
async function saveRoles(): Promise<void> {
  const id = selectedId.value
  if (!id) return
  roleSaving.value = true
  roleError.value = ''
  try {
    await sysApi.bindDeptRoles(id, { roleIds: [...draftRoleIds.value] })
    ui.ok('已保存绑定，该单位成员下次请求即按新角色判定')
    // 重新拉取：覆盖式保存最终以服务端结果为准（后端会做去重与合法性过滤）
    await loadRoles(id)
  } catch (e) {
    roleError.value = e instanceof ApiError ? e.message : '保存失败，请稍后重试'
  } finally {
    roleSaving.value = false
  }
}

// —— 新增 / 编辑 ——

/** 同级末尾 +1，省得每次手填还容易撞号；用户仍可改。 */
function nextSortOrder(parentId: string): number {
  const parent = findDept(tree.value, parentId)
  const siblings = parent ? childrenOf(parent) : tree.value
  return siblings.reduce((max, dept) => Math.max(max, dept.sortOrder), -1) + 1
}

/**
 * 打开新增弹窗。
 *
 * @param parentId 预选上级，`"0"` = 顶级（后端约定根部门 parentId 为 "0"）
 */
function openCreate(parentId: string): void {
  formMode.value = 'create'
  formId.value = ''
  formName.value = ''
  formParentId.value = parentId
  formSortOrder.value = String(nextSortOrder(parentId))
  formFieldError.value = ''
  formError.value = ''
  formOpen.value = true
}

function openEdit(dept: DeptVo): void {
  formMode.value = 'edit'
  formId.value = dept.id
  formName.value = dept.name
  formParentId.value = dept.parentId
  formSortOrder.value = String(dept.sortOrder)
  formFieldError.value = ''
  formError.value = ''
  formOpen.value = true
}

/** 排序号：空 = 0（与后端默认值一致），非整数或负数在前端就拦下。 */
function parseSortOrder(): number | null {
  const raw = formSortOrder.value.trim()
  if (raw === '') return 0
  const value = Number(raw)
  if (!Number.isInteger(value) || value < 0) return null
  return value
}

async function submitForm(): Promise<void> {
  formFieldError.value = ''
  formError.value = ''
  const name = formName.value.trim()
  if (!name) {
    formFieldError.value = '请填写单位名称'
    return
  }
  const sortOrder = parseSortOrder()
  if (sortOrder === null) {
    formFieldError.value = '排序号需为不小于 0 的整数'
    return
  }
  const body: DeptDtoReq = { name, parentId: formParentId.value, sortOrder }
  formBusy.value = true
  try {
    if (formMode.value === 'create') {
      const created = await sysApi.createDept(body)
      ui.ok(`已新增单位「${created.name}」`)
      formOpen.value = false
      await load()
      // 新建的单位直接选中，省得用户再去树里找一遍
      selectDept(created.id)
    } else {
      const saved = await sysApi.updateDept(formId.value, body)
      ui.ok(`已保存单位「${saved.name}」`)
      formOpen.value = false
      await load()
    }
  } catch (e) {
    // 后端文案原样展示：400「同级部门下已存在同名部门」/「不能将部门移动到其子部门下」
    if (e instanceof ApiError) {
      if (e.isBadRequest) formFieldError.value = e.message
      else formError.value = e.message
    } else {
      formError.value = '保存失败，请稍后重试'
    }
  } finally {
    formBusy.value = false
  }
}

// —— 删除 ——

function openDelete(dept: DeptVo): void {
  deleteTarget.value = dept
  deleteError.value = ''
  deleteConflict.value = false
}

async function confirmDelete(): Promise<void> {
  const target = deleteTarget.value
  if (!target) return
  deleteBusy.value = true
  deleteError.value = ''
  deleteConflict.value = false
  try {
    await sysApi.deleteDept(target.id)
    ui.ok(`已删除单位「${target.name}」`)
    deleteTarget.value = null
    // 删掉的正是当前选中项时清空选中，交给 load() 退回第一个根单位
    if (selectedId.value === target.id) selectedId.value = ''
    await load()
  } catch (e) {
    // 弹窗**不关**：409「该部门下仍有子部门或员工，请先转移」这类文案留在原地给用户看，
    // 关掉弹窗再飘一条 toast，用户得对着空列表猜自己删的是哪个单位
    if (e instanceof ApiError) {
      deleteError.value = e.message
      deleteConflict.value = e.isConflict
    } else {
      deleteError.value = '删除失败，请稍后重试'
    }
  } finally {
    deleteBusy.value = false
  }
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>组织机构</h1>
        <div class="desc">
          维护校内单位树，并给单位绑定角色 —— 单位成员自动继承所绑角色（演示数据：信息化中心 → STAFF + DOC_ADMIN）。
          绑角色是覆盖式保存，会影响该单位全体成员的有效权限。
        </div>
      </div>
      <div class="acts">
        <button v-if="canAddDept" class="btn btn-primary" @click="openCreate('0')">新增单位</button>
      </div>
    </div>

    <div class="grid g-list">
      <!-- ============ 左：机构树 ============ -->
      <div class="card">
        <div class="card-hd">
          <h3>机构树</h3>
          <span class="sub">{{ allRows.length }} 个单位</span>
          <div class="acts">
            <button class="btn btn-sm" :disabled="loading" @click="load">刷新</button>
          </div>
        </div>

        <StateBlock v-if="loading" kind="loading" :rows="3" />
        <StateBlock
          v-else-if="noperm"
          kind="noperm"
          title="没有机构浏览权限"
          desc="机构树接口需要权限点 sys:dept，请联系系统管理员开通。"
        />
        <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
          <button class="btn btn-sm" @click="load">重新加载</button>
        </StateBlock>
        <StateBlock
          v-else-if="!tree.length"
          kind="empty"
          title="暂无部门"
          desc="点上方的「新增单位」建第一个单位，上级选「顶级」；也可以先执行 sql/data.sql 灌入演示数据。"
        />

        <div v-else class="tree">
          <div
            v-for="row in visibleRows"
            :key="row.dept.id"
            class="node"
            :class="[levelClass(row.depth), { on: row.dept.id === selectedId }]"
            @click="selectDept(row.dept.id)"
          >
            <button
              v-if="row.hasChildren"
              type="button"
              class="caret cursor-pointer border-0 bg-transparent p-0"
              :title="collapsed.includes(row.dept.id) ? '展开下级' : '收起下级'"
              @click.stop="toggleCollapse(row.dept.id)"
            >
              {{ collapsed.includes(row.dept.id) ? '▸' : '▾' }}
            </button>
            <span v-else class="caret"></span>
            <span>{{ row.dept.name }}</span>
            <span v-if="row.hasChildren" class="cnt">{{ childrenOf(row.dept).length }} 个下级</span>
            <span class="code">#{{ row.dept.id }}</span>
          </div>
        </div>
      </div>

      <!-- ============ 右：单位详情 ============ -->
      <div>
        <StateBlock
          v-if="!selectedDept"
          kind="empty"
          title="请先选择一个单位"
          desc="在左侧机构树里点一个单位，这里会显示它的基本信息、绑定角色与成员名单。"
        />

        <template v-else>
          <div class="card">
            <div class="card-hd">
              <h3>{{ selectedDept.name }}</h3>
              <span class="sub">单位 #{{ selectedDept.id }}</span>
              <div class="acts">
                <button v-if="canAddDept" class="btn btn-sm" @click="openCreate(selectedDept.id)">新增下级</button>
                <button v-if="canEditDept" class="btn btn-sm" @click="openEdit(selectedDept)">编辑</button>
                <button v-if="canDeleteDept" class="btn btn-sm btn-danger" @click="openDelete(selectedDept)">删除</button>
              </div>
            </div>
            <div class="card-bd">
              <div class="kv">
                <div class="k">单位名称</div>
                <div>{{ selectedDept.name }}</div>
                <div class="k">上级单位</div>
                <div>{{ parentName }}</div>
                <div class="k">排序号</div>
                <div>{{ selectedDept.sortOrder }}</div>
                <div class="k">创建时间</div>
                <div>{{ formatDateTime(selectedDept.createdAt) }}</div>
                <div class="k">更新时间</div>
                <div>{{ formatDateTime(selectedDept.updatedAt) }}</div>
              </div>
              <div v-if="!canEditDept && !canDeleteDept" class="noperm mt12">
                你缺少「编辑机构 / 删除机构」所需的权限点，这里只能查看。改名与调序需
                <span class="mono">sys:dept:edit</span>，删除需 <span class="mono">sys:dept:delete</span>。
              </div>
            </div>
          </div>

          <!-- 该单位绑定的角色 -->
          <div class="card">
            <div class="card-hd">
              <h3>该单位绑定的角色</h3>
              <span class="sub">
                <template v-if="canGrantRole">覆盖式保存 · 已选 {{ draftRoleIds.length }} 个</template>
                <template v-else>只读</template>
              </span>
              <div class="acts">
                <button
                  v-if="canGrantRole"
                  class="btn btn-sm btn-primary"
                  :disabled="roleSaving || roleLoading || !roleDirty"
                  @click="saveRoles"
                >
                  {{ roleSaving ? '保存中…' : '保存绑定' }}
                </button>
              </div>
            </div>

            <div class="card-bd">
              <StateBlock v-if="roleLoading" kind="loading" :rows="3" />
              <StateBlock v-else-if="roleError" kind="error" :desc="roleError">
                <button class="btn btn-sm" @click="reloadDetail">重新加载</button>
              </StateBlock>

              <!-- 有 sys:role:grant：可勾选 -->
              <template v-else-if="canGrantRole">
                <div v-if="!allRoles.length" class="noperm">
                  读不到角色清单（「角色列表」接口需要权限点 <span class="mono">sys:role</span>），暂时只能按 ID 展示已绑角色：{{
                    boundRoleIds.length ? boundRoleIds.join('、') : '（未绑定任何角色）'
                  }}
                </div>
                <template v-else>
                  <div class="perm-grid">
                    <button
                      v-for="r in allRoles"
                      :key="r.id"
                      type="button"
                      class="perm-cell cursor-pointer border-0 bg-transparent text-left"
                      :class="{ on: draftRoleIds.includes(r.id) }"
                      @click="toggleRole(r.id)"
                    >
                      <span class="checkbox" :class="{ on: draftRoleIds.includes(r.id) }"></span>
                      <span class="nm">{{ r.name }}</span>
                      <span class="mono t3">{{ r.code }}</span>
                    </button>
                  </div>
                  <div class="hint mt12">
                    勾选即草稿，按「保存绑定」才提交；<strong>没勾的角色会被解绑</strong>。
                    保存后该单位成员无需重新登录，下一次请求即按新角色判定。
                    <span v-if="roleDirty" class="t3">当前有未保存的改动。</span>
                  </div>
                </template>
              </template>

              <!-- 缺 sys:role:grant：只读展示 + 说明原因（§8.12：缺权限不渲染「绑定角色」） -->
              <template v-else>
                <div v-if="boundRoleLabels.length" class="row wrap gap8">
                  <span v-for="label in boundRoleLabels" :key="label" class="tag">{{ label }}</span>
                </div>
                <div v-else class="t3">该单位当前未绑定任何角色。</div>
                <div class="noperm mt12">
                  你缺少权限点 <span class="mono">sys:role:grant</span>，所以只能查看、不能修改该单位的角色绑定。
                  绑角色会改变该单位全体成员的有效权限，因此这条写权限是单独设点的。
                </div>
                <div v-if="!canReadRoles" class="hint mt8">
                  「角色列表」接口另需权限点 <span class="mono">sys:role</span>，因此上方的角色只能按名称显示到什么程度取决于该权限。
                </div>
              </template>
            </div>
          </div>

          <!-- 单位成员 -->
          <div class="card">
            <div class="card-hd">
              <h3>单位成员</h3>
              <span class="sub">
                <template v-if="memberDenied">需要权限点 sys:user</template>
                <template v-else>共 {{ memberTotal }} 人</template>
              </span>
              <div class="acts">
                <button v-if="!memberDenied" class="btn btn-sm" :disabled="memberLoading" @click="loadMembers(selectedId)">
                  刷新
                </button>
              </div>
            </div>

            <StateBlock
              v-if="memberDenied"
              kind="noperm"
              title="看不到成员名单"
              desc="用户列表接口（GET /api/users）需要权限点 sys:user，它与机构管理权限点 sys:dept 是分开的两个点。"
            />
            <StateBlock v-else-if="memberLoading" kind="loading" :rows="5" />
            <StateBlock v-else-if="memberError" kind="error" :desc="memberError">
              <button class="btn btn-sm" @click="loadMembers(selectedId)">重新加载</button>
            </StateBlock>
            <StateBlock
              v-else-if="!members.length"
              kind="empty"
              title="该单位暂无成员"
              desc="成员取自用户档案上的所属单位；下级单位的成员不计入这里。"
            />

            <template v-else>
              <table class="tbl">
                <thead>
                  <tr>
                    <th class="w-fix2">工号</th>
                    <th class="w-fix2">姓名</th>
                    <th>角色</th>
                    <th class="w-fix2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="m in members" :key="m.id">
                    <td class="mono">{{ m.username }}</td>
                    <td>{{ m.realName }}</td>
                    <td>
                      <div v-if="m.roles.length" class="row wrap gap8">
                        <span v-for="code in m.roles" :key="code" class="tag">{{ roleLabel(code) }}</span>
                      </div>
                      <span v-else class="t3">—</span>
                    </td>
                    <td><StatusBadge :status="m.status" /></td>
                  </tr>
                </tbody>
              </table>
              <Pager
                :page-num="memberPage"
                :page-size="memberPageSize"
                :total="memberTotal"
                @change="changeMemberPage"
              />
            </template>

            <div class="card-bd tight xs t3">
              成员名单是「直接挂在该单位」的用户；单位绑定角色属于<strong>继承</strong>来源，成员自身的角色直授不在这里维护
              —— 那在「用户管理」页。
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 新增 / 编辑单位 -->
    <div v-if="formOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">{{ formMode === 'create' ? '新增单位' : '编辑单位' }}</div>
        <div class="modal-bd">
          <div v-if="formError" class="alert a-danger mb12"><span class="ico">!</span><span>{{ formError }}</span></div>

          <div class="field mb12">
            <label class="label" for="deptName">单位名称 <span class="req">*</span></label>
            <input
              id="deptName"
              v-model="formName"
              class="input"
              :class="{ err: formFieldError }"
              maxlength="64"
              placeholder="如：网络运行科"
            />
            <!-- 400 的文案贴着输入框展示（后端：同级部门下已存在同名部门） -->
            <div v-if="formFieldError" class="errtxt">{{ formFieldError }}</div>
            <div v-else class="hint">同一上级下不可重名，最长 64 个字符。</div>
          </div>

          <div class="field mb12">
            <label class="label" for="deptParent">上级单位</label>
            <select id="deptParent" v-model="formParentId" class="select">
              <option value="0">顶级（无上级）</option>
              <option v-for="row in parentChoices" :key="row.dept.id" :value="row.dept.id">
                {{ indent(row.depth) }}{{ row.dept.name }}
              </option>
            </select>
            <div class="hint">编辑时不会列出自己与自己的下级（后端也会以 400「不能将部门移动到其子部门下」拦住）。</div>
          </div>

          <div class="field">
            <label class="label" for="deptSort">排序号</label>
            <input id="deptSort" v-model="formSortOrder" class="input" type="number" min="0" step="1" />
            <div class="hint">同级内数字越小越靠前，需为不小于 0 的整数；留空按 0 处理。</div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn" :disabled="formBusy" @click="formOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="formBusy" @click="submitForm">
            {{ formBusy ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除：二次确认 -->
    <div v-if="deleteTarget" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">删除单位确认</div>
        <div class="modal-bd">
          <p class="mb12">
            即将删除单位「<b>{{ deleteTarget.name }}</b>」（#{{ deleteTarget.id }}），该单位的角色绑定会一并清理，
            <b>不可恢复</b>。
          </p>
          <!-- 失败文案来自后端 message，原样展示；409 是可纠正的业务冲突，用警示色而不是错误色 -->
          <div v-if="deleteError" class="alert mb12" :class="deleteConflict ? 'a-warn' : 'a-danger'">
            <span class="ico">!</span><span>{{ deleteError }}</span>
          </div>
          <div class="hint">若该单位下还有子单位或成员，后端会拒绝删除 —— 请先把他们转移到别的单位。</div>
        </div>
        <div class="modal-ft">
          <button class="btn" :disabled="deleteBusy" @click="deleteTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="deleteBusy" @click="confirmDelete">
            {{ deleteBusy ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
