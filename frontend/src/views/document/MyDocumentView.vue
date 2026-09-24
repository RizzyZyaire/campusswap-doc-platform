<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import ImportMarkdownButton from '@/components/ImportMarkdownButton.vue'
import MarkdownImportDialog from '@/components/MarkdownImportDialog.vue'
import Pager from '@/components/Pager.vue'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import * as taxApi from '@/api/taxonomy'
import { useUiStore } from '@/stores/ui'
import { useImportQueueStore } from '@/stores/importQueue'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { relativeTime } from '@/utils/format'
import type { DocumentStatus, DocumentVo, TagVo } from '@/types'

/**
 * 我的文档（§8.6）：六个 tab。
 * 五个走 /documents/mine（status 区分），收藏走 /favorites，回收站走 /documents/trash。
 */
type TabId = 'all' | 'draft' | 'published' | 'archived' | 'favorite' | 'trash'

interface TabDef {
  id: TabId
  label: string
  perm?: string
}

const ui = useUiStore()
const user = useUserStore()
const router = useRouter()
const importQueue = useImportQueueStore()

/** 批量导入弹窗（选完文件后打开：可逐篇改标题、统一选分类与标签，再决定直接建还是逐篇编辑）。 */
const importFiles = ref<{ name: string; text: string }[]>([])
const importCategories = ref<{ id: string; label: string }[]>([])
const importTags = ref<TagVo[]>([])

/**
 * 上传的文件 → 打开批量导入弹窗。
 *
 * <p>分类与标签在这里一次性取好（弹窗里要用）；取不到就退化成"未分类 / 无标签"，不阻断导入。</p>
 *
 * @param payload 读好的文件（可多篇）
 */
async function onImported(payload: { name: string; text: string }[]): Promise<void> {
  importFiles.value = payload
  try {
    const tree = await taxApi.categoryTree()
    const out: { id: string; label: string }[] = []
    const walk = (nodes: typeof tree, depth: number): void => {
      for (const n of nodes) {
        out.push({ id: n.id, label: '\u3000'.repeat(depth) + n.name })
        if (n.children?.length) walk(n.children, depth + 1)
      }
    }
    walk(tree, 0)
    importCategories.value = out
  } catch {
    importCategories.value = []
  }
  try {
    importTags.value = (await taxApi.tagList({ pageNum: 1, pageSize: 100 })).list
  } catch {
    importTags.value = []
  }
}

/**
 * 批量导入结束：刷新列表；若用户选了"逐篇编辑"，把新文档放进待编辑队列并跳到第一篇。
 *
 * @param payload 新建成功的文档与模式
 */
async function onImportDone(payload: { created: { id: string; title: string }[]; mode: 'list' | 'edit' }): Promise<void> {
  importFiles.value = []
  await load()
  if (payload.mode === 'edit' && payload.created.length) {
    importQueue.start(payload.created)
    const first = importQueue.shift()
    if (first) await router.push({ name: 'docs-edit', params: { id: first.id } })
  }
}

const tabs: TabDef[] = [
  { id: 'all', label: '全部' },
  { id: 'draft', label: '草稿' },
  { id: 'published', label: '已发布' },
  { id: 'archived', label: '已归档' },
  { id: 'favorite', label: '收藏', perm: PERM.docFavorite },
  { id: 'trash', label: '回收站', perm: PERM.docRestore }
]
const visibleTabs = computed(() => tabs.filter((t) => !t.perm || user.hasPerm(t.perm)))

const active = ref<TabId>('all')
const keyword = ref('')
const list = ref<DocumentVo[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const errorText = ref('')

/** 彻底删除的二次确认：必须输入标题（BR-08）。 */
const destroyTarget = ref<DocumentVo | null>(null)
const destroyInput = ref('')
const destroyBusy = ref(false)

const statusOf = (tab: TabId): DocumentStatus | undefined => {
  if (tab === 'draft') return 'DRAFT'
  if (tab === 'published') return 'PUBLISHED'
  if (tab === 'archived') return 'ARCHIVED'
  return undefined
}

async function load(): Promise<void> {  loading.value = true
  errorText.value = ''
  try {
    const kw = keyword.value.trim() || undefined
    if (active.value === 'favorite') {
      const page = await docApi.favoriteDocuments({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: kw })
      list.value = page.list
      total.value = page.total
    } else if (active.value === 'trash') {
      const page = await docApi.trashDocuments({ pageNum: pageNum.value, pageSize: pageSize.value, keyword: kw })
      list.value = page.list
      total.value = page.total
    } else {
      const page = await docApi.myDocuments({
        pageNum: pageNum.value,
        pageSize: pageSize.value,
        keyword: kw,
        status: statusOf(active.value)
      })
      list.value = page.list
      total.value = page.total
    }
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '加载失败，请稍后重试'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function switchTab(id: TabId): void {
  active.value = id
  pageNum.value = 1
  void load()
}

async function publish(d: DocumentVo): Promise<void> {
  try {
    await docApi.publishDocument(d.id)
    ui.ok('已提交发布，等待文档管理员审核')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '发布失败')
  }
}

async function restore(d: DocumentVo): Promise<void> {
  try {
    await docApi.restoreDocument(d.id)
    ui.ok('已恢复到草稿')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '恢复失败')
  }
}

async function moveToTrash(d: DocumentVo): Promise<void> {
  try {
    await docApi.moveToTrash(d.id)
    ui.ok('已移入回收站')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '删除失败')
  }
}

async function confirmDestroy(): Promise<void> {
  const target = destroyTarget.value
  if (!target) return
  if (destroyInput.value.trim() !== target.title) {
    ui.warn('请输入完整标题以确认彻底删除')
    return
  }
  destroyBusy.value = true
  try {
    await docApi.destroyDocument(target.id, { confirm: true })
    ui.ok('已彻底删除，不可恢复')
    destroyTarget.value = null
    destroyInput.value = ''
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '彻底删除失败')
  } finally {
    destroyBusy.value = false
  }
}

function openDestroy(d: DocumentVo): void {
  destroyTarget.value = d
  destroyInput.value = ''
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>我的文档</h1>
        <div class="desc">按状态分 tab 管理本人文档；回收站里的文档可恢复，或输入完整标题后彻底删除（不可恢复）。</div>
      </div>
      <div class="acts">
        <!-- 两个入口：① 在网站上写（编辑器）；② 上传现成的 Markdown 文件直接建草稿（用户反馈"只有新建、没有上传"） -->
        <ImportMarkdownButton v-if="user.hasPerm(PERM.docCreate)" label="上传 Markdown" multiple @loaded="onImported" />
        <RouterLink v-if="user.hasPerm(PERM.docCreate)" class="btn btn-primary" to="/docs/edit">新建文档</RouterLink>
      </div>
    </div>

    <div class="card">
      <div class="tabs">
        <button
          v-for="t in visibleTabs"
          :key="t.id"
          class="tab"
          :class="{ active: active === t.id }"
          @click="switchTab(t.id)"
        >
          {{ t.label }}
        </button>
      </div>

      <div class="toolbar">
        <input v-model="keyword" class="input grow" placeholder="搜索标题" @keyup.enter="pageNum = 1; load()" />
        <button class="btn btn-sm btn-primary" @click="pageNum = 1; load()">查询</button>
      </div>

      <StateBlock v-if="loading" kind="loading" :rows="6" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!list.length" kind="empty" title="这里还没有文档" desc="换个 tab，或用「新建文档」写一篇。" />

      <template v-else>
        <table class="tbl">
          <thead>
            <tr>
              <th>文档</th>
              <th class="w-fix2">状态</th>
              <th class="w-fix">版本</th>
              <th class="w-fix">阅读</th>
              <th class="w-fix2">更新时间</th>
              <th class="w-fix3"></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in list" :key="d.id">
              <td>
                <RouterLink class="title" :to="`/docs/${d.id}`">{{ d.title }}</RouterLink>
                <span class="sub">{{ d.categoryName ?? '未分类' }}</span>
              </td>
              <td><StatusBadge :status="d.status" /></td>
              <td class="t2">v{{ d.versionNum }}</td>
              <td class="num t2">{{ d.viewCount }}</td>
              <td class="sm t2">{{ relativeTime(d.updatedAt) }}</td>
              <td class="acts">
                <template v-if="active !== 'trash'">
                  <RouterLink v-if="d.canEdit" class="link" :to="`/docs/edit/${d.id}`">编辑</RouterLink>
                  <button v-if="d.status === 'DRAFT' && user.hasPerm(PERM.docPublish)" class="link" @click="publish(d)">
                    提交发布
                  </button>
                  <button v-if="user.hasPerm(PERM.docDelete)" class="link danger" @click="moveToTrash(d)">删除</button>
                </template>
                <template v-else>
                  <button v-if="user.hasPerm(PERM.docRestore)" class="link" @click="restore(d)">恢复</button>
                  <button v-if="user.hasPerm(PERM.docDelete)" class="link danger" @click="openDestroy(d)">彻底删除</button>
                </template>
              </td>
            </tr>
          </tbody>
        </table>
        <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="(p: number) => { pageNum = p; load() }" />
      </template>
    </div>

    <!-- 彻底删除：输入完整标题确认（BR-08） -->
    <div v-if="destroyTarget" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">彻底删除确认</div>
        <div class="modal-bd">
          <p class="mb12">
            即将彻底删除《<b>{{ destroyTarget.title }}</b>》，正文、版本留痕与收藏关系都会一并清理，<b>不可恢复</b>。
          </p>
          <label class="label" for="destroyConfirm">请输入完整标题以确认</label>
          <input id="destroyConfirm" v-model="destroyInput" class="input" :placeholder="destroyTarget.title" />
        </div>
        <div class="modal-ft">
          <button class="btn" @click="destroyTarget = null">取消</button>
          <button
            class="btn btn-danger"
            :disabled="destroyBusy || destroyInput.trim() !== destroyTarget.title"
            @click="confirmDestroy"
          >
            {{ destroyBusy ? '删除中…' : '确认彻底删除' }}
          </button>
        </div>
      </div>
    </div>
    <!-- 批量导入弹窗：逐篇改标题 + 统一分类/标签 + 两种落地方式 -->
    <MarkdownImportDialog
      v-if="importFiles.length"
      :files="importFiles"
      :categories="importCategories"
      :tags="importTags"
      @close="importFiles = []"
      @done="onImportDone"
    />
  </div>
</template>
