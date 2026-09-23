<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import Pager from '@/components/Pager.vue'
import StateBlock from '@/components/StateBlock.vue'
import { ApiError } from '@/api/request'
import * as taxApi from '@/api/taxonomy'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { relativeTime } from '@/utils/format'
import type { CategoryVo, TagVo } from '@/types'

/**
 * 分类与标签（UI_UX_SPECIFICATION §8.9）：左分类树 / 右标签表。
 *
 * <p>写入口按权限点分成两路：`doc:category:edit` 管分类的新增 / 改名 / 调序 / 删除，
 * `doc:tag:edit` 管标签的新增 / 改名 / 删除；两者都没有时整页只读，并写明缺的是哪个权限点。</p>
 *
 * <p>失败文案一律用服务端返回的 `message` 原文（UI_UX_SPECIFICATION I5）：
 * 400「不能将节点移动到其子节点下」、409「该分类下仍有文档，无法删除」都由后端给出，前端不改写、不美化。</p>
 */

/** 树拍平后的一行：模板只需要「节点 + 层级 + 有没有子节点」，递归统一在 flatten 里做完。 */
interface TreeRow {
  node: CategoryVo
  depth: number
  hasChildren: boolean
}

/** 分类弹窗的意图：三者的字段完全相同（名称 / 上级 / 排序号），只有标题与提示语不同。 */
type CategoryIntent = 'create' | 'rename' | 'sort'

/** 待删除对象：分类与标签共用一套二次确认弹窗。 */
interface DeleteTarget {
  kind: 'category' | 'tag'
  id: string
  name: string
}

const ui = useUiStore()
const user = useUserStore()

const canEditCategory = computed(() => user.hasPerm(PERM.docCategoryEdit))
const canEditTag = computed(() => user.hasPerm(PERM.docTagEdit))

/** 缺哪些维护权限（页面顶部那句「为什么没有按钮」要逐字写出来）。 */
const missingPermText = computed(() =>
  [canEditCategory.value ? '' : PERM.docCategoryEdit, canEditTag.value ? '' : PERM.docTagEdit].filter(Boolean).join('、')
)

/* ------------------------------- 分类树 ------------------------------- */

const tree = ref<CategoryVo[]>([])
const treeLoading = ref(true)
const treeError = ref('')
const treeNoperm = ref(false)
const selectedId = ref('')
/** 折叠的节点 id：分类树最多 3 层，默认全展开比逐层点开省事，所以记「折叠」而不是「展开」。 */
const collapsedIds = ref<string[]>([])

/** 深度优先查找（选中态、上级名称、同级计数都要用它）。 */
function findNode(nodes: CategoryVo[], id: string): CategoryVo | null {
  for (const n of nodes) {
    if (n.id === id) return n
    const hit = findNode(n.children ?? [], id)
    if (hit) return hit
  }
  return null
}

/** 统计全树节点数（卡片头显示"共 N 个分类"）。 */
function countNodes(nodes: CategoryVo[]): number {
  return nodes.reduce((sum, n) => sum + 1 + countNodes(n.children ?? []), 0)
}

/** 有子节点的节点 id（「全部收起」要用）。 */
function parentIds(nodes: CategoryVo[]): string[] {
  const out: string[] = []
  for (const n of nodes) {
    if ((n.children?.length ?? 0) > 0) out.push(n.id, ...parentIds(n.children))
  }
  return out
}

/** 按折叠状态拍平：折叠的子树直接不进渲染列表。 */
const flatRows = computed<TreeRow[]>(() => {
  const out: TreeRow[] = []
  const walk = (nodes: CategoryVo[], depth: number): void => {
    for (const n of nodes) {
      const hasChildren = (n.children?.length ?? 0) > 0
      out.push({ node: n, depth, hasChildren })
      if (hasChildren && !collapsedIds.value.includes(n.id)) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

const categoryTotal = computed(() => countNodes(tree.value))
/** 选中节点从整棵树取（而不是从 flatRows）：收起父节点后不能把选中态一起弄丢。 */
const selected = computed<CategoryVo | null>(() => findNode(tree.value, selectedId.value))
const selectedParentName = computed(() => {
  const node = selected.value
  if (!node) return '—'
  if (node.parentId === '0') return '顶级分类'
  return findNode(tree.value, node.parentId)?.name ?? `#${node.parentId}`
})

function isCollapsed(id: string): boolean {
  return collapsedIds.value.includes(id)
}

/**
 * 树的层级类名。
 *
 * <p>刻意写成三个**字面量**而不是 `'lv' + (depth + 1)`：Tailwind 只保留"源码里出现过的类名"
 * 对应的 `@layer components` 规则，拼接出来的名字一旦全项目没有别处出现，就会被摇掉
 * （开发时看着正常，构建后缩进消失）。</p>
 */
function lvClass(depth: number): string {
  if (depth === 0) return 'lv1'
  if (depth === 1) return 'lv2'
  return 'lv3'
}

function toggleCollapse(id: string): void {
  collapsedIds.value = isCollapsed(id)
    ? collapsedIds.value.filter((x) => x !== id)
    : [...collapsedIds.value, id]
}

/** 「全部收起 ↔ 全部展开」两态切换。 */
function toggleAll(): void {
  collapsedIds.value = collapsedIds.value.length ? [] : parentIds(tree.value)
}

async function loadTree(): Promise<void> {
  treeLoading.value = true
  treeError.value = ''
  treeNoperm.value = false
  try {
    const data = await taxApi.categoryTree()
    tree.value = data
    // 重拉后校验本地状态：选中的节点可能已被删除，折叠的节点也可能不存在了
    if (!findNode(data, selectedId.value)) selectedId.value = data[0]?.id ?? ''
    collapsedIds.value = collapsedIds.value.filter((id) => findNode(data, id) !== null)
  } catch (e) {
    tree.value = []
    if (e instanceof ApiError && e.isForbidden) treeNoperm.value = true
    treeError.value = e instanceof ApiError ? e.message : '分类树加载失败，请稍后重试'
  } finally {
    treeLoading.value = false
  }
}

/* ------------------------------- 标签表 ------------------------------- */

const tags = ref<TagVo[]>([])
const tagTotal = ref(0)
const tagPageNum = ref(1)
const tagPageSize = ref(10)
const tagKeyword = ref('')
const tagLoading = ref(true)
const tagListError = ref('')
const tagNoperm = ref(false)

async function loadTags(): Promise<void> {
  tagLoading.value = true
  tagListError.value = ''
  tagNoperm.value = false
  try {
    const page = await taxApi.tagList({
      pageNum: tagPageNum.value,
      pageSize: tagPageSize.value,
      keyword: tagKeyword.value.trim() || undefined
    })
    tags.value = page.list
    tagTotal.value = page.total
  } catch (e) {
    tags.value = []
    tagTotal.value = 0
    if (e instanceof ApiError && e.isForbidden) tagNoperm.value = true
    tagListError.value = e instanceof ApiError ? e.message : '标签列表加载失败，请稍后重试'
  } finally {
    tagLoading.value = false
  }
}

function applyTagSearch(): void {
  tagPageNum.value = 1
  void loadTags()
}

function resetTagSearch(): void {
  tagKeyword.value = ''
  applyTagSearch()
}

function onTagPageChange(page: number): void {
  tagPageNum.value = page
  void loadTags()
}

/* ------------------------------ 分类弹窗 ------------------------------ */

const catOpen = ref(false)
const catIntent = ref<CategoryIntent>('create')
/** 编辑目标 id；为空串 = 新增。 */
const catTargetId = ref('')
const catForm = reactive({ name: '', parentId: '0', sortOrder: 0 })
/** 保存失败时的服务端 message 原文（显示成弹窗内红字）。 */
const catSaveError = ref('')
const catBusy = ref(false)

const catTitle = computed(() => {
  if (catIntent.value === 'rename') return '重命名分类'
  if (catIntent.value === 'sort') return '调整分类顺序'
  return '新增分类'
})

const catHint = computed(() => {
  if (catIntent.value === 'rename') return '改名只影响显示名称：分类 ID 不变，已归入该分类的文档无需迁移。'
  if (catIntent.value === 'sort') return '排序号只在同一上级内比较，数值越小越靠前；不改上级时只调排序号即可。'
  return '分类最多 3 层（BR-13）：选「顶级分类」建根节点，或选一个已有分类作为它的上级。'
})

/** 下拉选项：拍平整棵树，用全角空格缩进表示层级（`\u3000` 转义写，避免源码里出现不规则空白）。 */
const parentOptions = computed(() => {
  const out: { id: string; label: string }[] = []
  const walk = (nodes: CategoryVo[], depth: number): void => {
    for (const n of nodes) {
      out.push({ id: n.id, label: '\u3000'.repeat(depth) + n.name })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(tree.value, 0)
  return out
})

/** 同级已有节点数：新增时把排序号默认排到末尾，比一律给 0 更贴近「加在最后」的预期。 */
function siblingCount(parentId: string): number {
  if (parentId === '0') return tree.value.length
  return findNode(tree.value, parentId)?.children?.length ?? 0
}

function openCreate(parentId: string): void {
  catIntent.value = 'create'
  catTargetId.value = ''
  catForm.name = ''
  catForm.parentId = parentId
  catForm.sortOrder = siblingCount(parentId)
  catSaveError.value = ''
  catOpen.value = true
}

function openEdit(node: CategoryVo, intent: CategoryIntent): void {
  catIntent.value = intent
  catTargetId.value = node.id
  catForm.name = node.name
  catForm.parentId = node.parentId
  catForm.sortOrder = node.sortOrder
  catSaveError.value = ''
  catOpen.value = true
}

/** 400 / 409 = 「改一下就能过」的错误：留在弹窗里让用户直接改；其余（404 / 500 / 网络）关掉弹窗只提示。 */
function isFixable(e: unknown): boolean {
  return e instanceof ApiError && (e.isBadRequest || e.isConflict)
}

async function submitCategory(): Promise<void> {
  if (catBusy.value) return
  const name = catForm.name.trim()
  if (!name) {
    catSaveError.value = '分类名称不能为空'
    return
  }
  catBusy.value = true
  catSaveError.value = ''
  const editingId = catTargetId.value
  try {
    const body = { name, parentId: catForm.parentId, sortOrder: catForm.sortOrder }
    if (editingId) await taxApi.updateCategory(editingId, body)
    else await taxApi.createCategory(body)
    ui.ok(editingId ? '分类已更新' : '分类已新增')
    catOpen.value = false
    await loadTree()
  } catch (e) {
    const message = e instanceof ApiError ? e.message : '保存失败，请稍后重试'
    catSaveError.value = message
    // 校验 / 冲突类错误只写在弹窗里（同一句话不弹两次）；其余错误关掉弹窗并轻提示
    if (isFixable(e)) {
      // 本地从未先行改过树，重拉一次即是 §8.9 说的「本地回滚」：失败后界面 = 服务端真实状态
      await loadTree()
    } else {
      ui.err(message)
      catOpen.value = false
    }
  } finally {
    catBusy.value = false
  }
}

/* ------------------------------ 标签弹窗 ------------------------------ */

const tagOpen = ref(false)
const tagTargetId = ref('')
const tagForm = reactive({ name: '' })
const tagSaveError = ref('')
const tagBusy = ref(false)

function openTagCreate(): void {
  tagTargetId.value = ''
  tagForm.name = ''
  tagSaveError.value = ''
  tagOpen.value = true
}

function openTagRename(tag: TagVo): void {
  tagTargetId.value = tag.id
  tagForm.name = tag.name
  tagSaveError.value = ''
  tagOpen.value = true
}

async function submitTag(): Promise<void> {
  if (tagBusy.value) return
  const name = tagForm.name.trim()
  if (!name) {
    tagSaveError.value = '标签名称不能为空'
    return
  }
  tagBusy.value = true
  tagSaveError.value = ''
  const editingId = tagTargetId.value
  try {
    if (editingId) await taxApi.updateTag(editingId, { name })
    else await taxApi.createTag({ name })
    ui.ok(editingId ? '标签已重命名' : '标签已新增')
    tagOpen.value = false
    await loadTags()
  } catch (e) {
    const message = e instanceof ApiError ? e.message : '保存失败，请稍后重试'
    tagSaveError.value = message
    if (!isFixable(e)) {
      ui.err(message)
      tagOpen.value = false
    }
  } finally {
    tagBusy.value = false
  }
}

/* ---------------------------- 删除二次确认 ---------------------------- */

const delTarget = ref<DeleteTarget | null>(null)
const delError = ref('')
const delBusy = ref(false)

function askDeleteCategory(node: CategoryVo): void {
  delTarget.value = { kind: 'category', id: node.id, name: node.name }
  delError.value = ''
}

function askDeleteTag(tag: TagVo): void {
  delTarget.value = { kind: 'tag', id: tag.id, name: tag.name }
  delError.value = ''
}

async function confirmDelete(): Promise<void> {
  const target = delTarget.value
  if (!target || delBusy.value) return
  delBusy.value = true
  delError.value = ''
  try {
    if (target.kind === 'category') {
      await taxApi.deleteCategory(target.id)
      ui.ok('分类已删除')
      await loadTree()
    } else {
      await taxApi.deleteTag(target.id)
      ui.ok('标签已删除')
      await loadTags()
    }
    delTarget.value = null
  } catch (e) {
    // 删除弹窗里没有可改的字段，所以红字与轻提示都上：409「该分类下仍有文档，无法删除」一个字都不改写（I5）
    delError.value = e instanceof ApiError ? e.message : '删除失败，请稍后重试'
    ui.err(delError.value)
  } finally {
    delBusy.value = false
  }
}

onMounted(() => {
  void loadTree()
  void loadTags()
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>分类与标签</h1>
        <div class="desc">
          分类是文档的稳定归类维度（最多 3 层，同级按排序号升序）；标签是从正文里长出来的词，按使用次数倒序。
          维护入口按权限点显隐，没有权限就是只读浏览。
        </div>
      </div>
      <div class="acts">
        <button v-if="canEditCategory" class="btn" @click="openCreate('0')">新增分类</button>
        <button v-if="canEditTag" class="btn btn-primary" @click="openTagCreate">新增标签</button>
      </div>
    </div>

    <!-- 两个维护权限都没有：整页只读，并写明缺的是哪个权限点（§8.9 四态·无权限） -->
    <div v-if="!canEditCategory && !canEditTag" class="alert a-info mb16">
      <span class="ico">i</span>
      <span>
        你没有维护权限（{{ missingPermText }}），本页为只读浏览：
        分类与标签的新增 / 改名 / 调序 / 删除入口都不会渲染。
      </span>
    </div>

    <div class="grid g-list">
      <!-- 左：分类树 -->
      <div>
        <div class="card">
          <div class="card-hd">
            <h3>分类树</h3>
            <span class="sub">{{ categoryTotal }} 个分类</span>
            <div class="acts">
              <button v-if="tree.length" class="btn btn-sm" @click="toggleAll">
                {{ collapsedIds.length ? '全部展开' : '全部收起' }}
              </button>
              <button v-if="canEditCategory" class="btn btn-sm" @click="openCreate('0')">新增顶级分类</button>
            </div>
          </div>

          <!-- 加载：三级递减排布的骨架，与展开后的层级观感对得上 -->
          <div v-if="treeLoading" class="sk">
            <div class="sk-line w80"></div>
            <div class="sk-line w60"></div>
            <div class="sk-line w40"></div>
          </div>
          <StateBlock v-else-if="treeNoperm" kind="noperm" :desc="treeError" />
          <StateBlock v-else-if="treeError" kind="error" :desc="treeError">
            <button class="btn btn-sm" @click="loadTree">重新加载</button>
          </StateBlock>
          <StateBlock
            v-else-if="!tree.length"
            kind="empty"
            title="暂无分类"
            desc="分类体系还没有数据：可执行 backend/sql/data.sql 导入种子分类，或点右上角「新增顶级分类」。"
          />

          <template v-else>
            <div class="card-bd tight">
              <div class="tree">
                <div
                  v-for="row in flatRows"
                  :key="row.node.id"
                  class="node"
                  :class="[lvClass(row.depth), { on: row.node.id === selectedId }]"
                  @click="selectedId = row.node.id"
                >
                  <span v-if="row.hasChildren" class="caret" @click.stop="toggleCollapse(row.node.id)">
                    {{ isCollapsed(row.node.id) ? '▸' : '▾' }}
                  </span>
                  <span v-else class="caret"></span>
                  <span class="ellip">{{ row.node.name }}</span>
                  <span class="code">#{{ row.node.id }}</span>
                </div>
              </div>
            </div>

            <div class="card-hd">
              <h3>选中的分类</h3>
              <span class="sub">改名 / 调序 / 删除都在这里</span>
            </div>
            <div class="card-bd">
              <div v-if="selected" class="kv">
                <div class="k">名称</div>
                <div class="strong">{{ selected.name }}</div>
                <div class="k">上级分类</div>
                <div>{{ selectedParentName }}</div>
                <div class="k">排序号</div>
                <div>{{ selected.sortOrder }}</div>
                <div class="k">子分类</div>
                <div>{{ selected.children.length }} 个</div>
              </div>
              <div v-else class="xs t3">在左侧点选一个分类节点，这里显示它的上级、排序号与子分类数。</div>

              <div v-if="canEditCategory && selected" class="row wrap mt12">
                <button class="btn btn-sm" @click="openCreate(selected.id)">新增子分类</button>
                <button class="btn btn-sm" @click="openEdit(selected, 'rename')">改名</button>
                <button class="btn btn-sm" @click="openEdit(selected, 'sort')">调序</button>
                <button class="btn btn-sm btn-danger" @click="askDeleteCategory(selected)">删除</button>
              </div>
              <div v-else-if="!canEditCategory" class="noperm mt12">
                你没有维护权限（doc:category:edit），分类树只能浏览：新增 / 改名 / 调序 / 删除入口都不会渲染。
              </div>
            </div>
          </template>
        </div>
      </div>

      <!-- 右：标签表 -->
      <div>
        <div class="card">
          <div class="card-hd">
            <h3>标签</h3>
            <span class="sub">共 {{ tagTotal }} 个 · 按使用次数倒序（把同义标签改成同一个名字即为「合并」）</span>
            <div class="acts">
              <button v-if="canEditTag" class="btn btn-sm btn-primary" @click="openTagCreate">新增标签</button>
            </div>
          </div>

          <div class="toolbar">
            <input v-model="tagKeyword" class="input grow" placeholder="搜索标签名（≤64 字）" @keyup.enter="applyTagSearch" />
            <button class="btn btn-sm btn-primary" @click="applyTagSearch">查询</button>
            <button class="btn btn-sm" @click="resetTagSearch">重置</button>
          </div>

          <div v-if="!canEditTag" class="card-bd tight">
            <div class="noperm">你没有维护权限（doc:tag:edit），标签列表只能浏览：新增 / 改名 / 删除入口都不会渲染。</div>
          </div>

          <StateBlock v-if="tagLoading" kind="loading" :rows="5" />
          <StateBlock v-else-if="tagNoperm" kind="noperm" :desc="tagListError" />
          <StateBlock v-else-if="tagListError" kind="error" :desc="tagListError">
            <button class="btn btn-sm" @click="loadTags">重新加载</button>
          </StateBlock>
          <StateBlock
            v-else-if="!tags.length"
            kind="empty"
            title="暂无标签"
            desc="还没有任何标签：作者写文档打标时可以直接新建，或点右上角「新增标签」。"
          />

          <template v-else>
            <table class="tbl">
              <thead>
                <tr>
                  <th>标签</th>
                  <th class="w-fix">使用次数</th>
                  <th class="w-fix2">最近更新</th>
                  <th class="w-fix3"></th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in tags" :key="t.id">
                  <td>
                    <span class="title">{{ t.name }}</span>
                    <span class="sub">#{{ t.id }}</span>
                  </td>
                  <td class="num t2">{{ t.useCount }}</td>
                  <td class="sm t3">{{ relativeTime(t.updatedAt) }}</td>
                  <td class="acts">
                    <template v-if="canEditTag">
                      <button class="link" @click="openTagRename(t)">改名</button>
                      <button class="link danger" @click="askDeleteTag(t)">删除</button>
                    </template>
                    <span v-else class="link muted">只读</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <Pager :page-num="tagPageNum" :page-size="tagPageSize" :total="tagTotal" @change="onTagPageChange" />
          </template>
        </div>
      </div>
    </div>

    <!-- 分类新增 / 改名 / 调序：同一套字段（名称 + 上级 + 排序号） -->
    <div v-if="catOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">{{ catTitle }}</div>
        <div class="modal-bd">
          <div class="field mb12">
            <label class="label" for="catName">分类名称 <span class="req">*</span></label>
            <input
              id="catName"
              v-model="catForm.name"
              class="input"
              maxlength="64"
              placeholder="如：教学管理"
              :disabled="catBusy"
            />
          </div>
          <div class="field mb12">
            <label class="label" for="catParent">上级分类 <span class="req">*</span></label>
            <select id="catParent" v-model="catForm.parentId" class="select" :disabled="catBusy">
              <option value="0">顶级分类（根节点）</option>
              <option v-for="opt in parentOptions" :key="opt.id" :value="opt.id">{{ opt.label }}</option>
            </select>
            <div class="hint">提交时由服务端校验「不能移动到自己的子分类下」与「最多 3 层」。</div>
          </div>
          <div class="field">
            <label class="label" for="catSort">排序号</label>
            <input
              id="catSort"
              v-model.number="catForm.sortOrder"
              class="input"
              type="number"
              min="0"
              :disabled="catBusy"
            />
            <div class="hint">同级内按排序号升序，数值越小越靠前。</div>
          </div>
          <div class="hint mt12">{{ catHint }}</div>
          <!-- 服务端 message 原样显示（400 不能将节点移动到其子节点下 / 409 同级分类下已存在同名分类） -->
          <div v-if="catSaveError" class="errtxt mt8">{{ catSaveError }}</div>
        </div>
        <div class="modal-ft">
          <button class="btn" :disabled="catBusy" @click="catOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="catBusy || !catForm.name.trim()" @click="submitCategory">
            {{ catBusy ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 标签新增 / 改名：只有一个名称字段（改名即合并） -->
    <div v-if="tagOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">{{ tagTargetId ? '重命名标签' : '新增标签' }}</div>
        <div class="modal-bd">
          <div class="field">
            <label class="label" for="tagName">标签名称 <span class="req">*</span></label>
            <input
              id="tagName"
              v-model="tagForm.name"
              class="input"
              maxlength="16"
              placeholder="如：RBAC"
              :disabled="tagBusy"
            />
            <div class="hint">1–16 个字符，全局唯一；改成已有标签的名字即为「合并」，所有引用处自动生效。</div>
          </div>
          <div v-if="tagSaveError" class="errtxt mt8">{{ tagSaveError }}</div>
        </div>
        <div class="modal-ft">
          <button class="btn" :disabled="tagBusy" @click="tagOpen = false">取消</button>
          <button class="btn btn-primary" :disabled="tagBusy || !tagForm.name.trim()" @click="submitTag">
            {{ tagBusy ? '保存中…' : '保存' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 删除二次确认：遮罩不响应点击（必须显式选择），失败原因写在弹窗里 -->
    <div v-if="delTarget" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">删除确认</div>
        <div class="modal-bd">
          <p class="mb12">
            <template v-if="delTarget?.kind === 'category'">
              确定删除分类「<b>{{ delTarget?.name }}</b>」吗？它的下面若仍有子分类或未删除的文档，服务端会拒绝删除并说明原因。
            </template>
            <template v-else>
              确定删除标签「<b>{{ delTarget?.name }}</b>」吗？所有文档上的这个标签会一并清理，文档本身不受影响。
            </template>
          </p>
          <div v-if="delError" class="errtxt">{{ delError }}</div>
        </div>
        <div class="modal-ft">
          <button class="btn" :disabled="delBusy" @click="delTarget = null">取消</button>
          <button class="btn btn-danger" :disabled="delBusy" @click="confirmDelete">
            {{ delBusy ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
