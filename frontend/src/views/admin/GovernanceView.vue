<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import Pager from '@/components/Pager.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { CHANGE_TYPE_TEXT, formatDateTime, relativeTime } from '@/utils/format'
import type { DocumentDetailVo, DocumentSort, DocumentStatus, DocumentVersionVo, DocumentVo } from '@/types'

/**
 * 内容治理（UI_UX_SPECIFICATION §8.8，权限 `doc:manage`）。
 *
 * 概览卡 + 状态筛选（含回收站）+ 拟稿人筛选 + 全平台文档表 + 版本历史抽屉。
 *
 * 三处按后端契约做的事：
 *  ① 概览卡的数字是**每个状态各发一次 `pageSize=1` 的查询**取 `total` 得来的，不是前端猜的；
 *  ② 拟稿人下拉的候选来自**全量文档里出现过的作者**（用户目录接口要 `sys:user`，文档管理员没有），
 *     所以文案写清楚"来自文档"，不假装它是通讯录；
 *  ③ 彻底删除只能对回收站里的文档做（否则后端 400「仅回收站中的文档可以彻底删除」），
 *     所以治理页保留「移入回收站 → 彻底删除」两步，且删除要输入完整标题（BR-08）。
 */
const ui = useUiStore()
const user = useUserStore()

const filters = reactive<{ status: '' | DocumentStatus; authorId: string; keyword: string; sort: DocumentSort }>({
  status: '',
  authorId: '',
  keyword: '',
  sort: 'updatedAt_desc'
})
const list = ref<DocumentVo[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(true)
const errorText = ref('')
const noperm = ref(false)
const busy = ref(false)

/** 概览卡：各状态总数（`-1` = 该次查询失败，展示成「—」而不是假的 0）。 */
const counts = reactive({ all: 0, DRAFT: 0, PUBLISHED: 0, ARCHIVED: 0, TRASH: 0 })
/** 拟稿人候选（来自全量文档的作者）。 */
const authors = ref<{ id: string; name: string }[]>([])

const canArchive = computed(() => user.hasPerm(PERM.docArchive))
const canDelete = computed(() => user.hasPerm(PERM.docDelete))

/** 抽屉。 */
const current = ref<DocumentDetailVo | null>(null)
const versions = ref<DocumentVersionVo[]>([])
const drawerLoading = ref(false)
const drawerError = ref('')

const SORTS: { value: DocumentSort; label: string }[] = [
  { value: 'updatedAt_desc', label: '最近更新' },
  { value: 'publishAt_desc', label: '发布时间' },
  { value: 'viewCount_desc', label: '阅读量' }
]

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  noperm.value = false
  try {
    const page = await docApi.manageDocuments({
      status: filters.status || undefined,
      authorId: filters.authorId || undefined,
      keyword: filters.keyword.trim() || undefined,
      sort: filters.sort,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = page.list
    total.value = page.total
    void loadCounts()
    void loadAuthors()
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

/** 概览卡的 5 个数字：各发一次 `pageSize=1` 的查询，只取 `total`。 */
async function loadCounts(): Promise<void> {
  const statuses: ('' | DocumentStatus)[] = ['', 'DRAFT', 'PUBLISHED', 'ARCHIVED', 'TRASH']
  const pages = await Promise.all(
    statuses.map((s) =>
      docApi
        .manageDocuments({ status: s || undefined, pageNum: 1, pageSize: 1 })
        .then((p) => p.total)
        .catch(() => -1)
    )
  )
  counts.all = pages[0]
  counts.DRAFT = pages[1]
  counts.PUBLISHED = pages[2]
  counts.ARCHIVED = pages[3]
  counts.TRASH = pages[4]
}

/** 拟稿人候选：拉一页大页的全量文档，按 `authorId` 去重。 */
async function loadAuthors(): Promise<void> {
  try {
    const page = await docApi.manageDocuments({ pageNum: 1, pageSize: 200 })
    const seen = new Map<string, string>()
    for (const d of page.list) {
      if (d.authorId && d.authorName) seen.set(d.authorId, d.authorName)
    }
    authors.value = [...seen.entries()]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name))
  } catch {
    authors.value = []
  }
}

async function open(id: string): Promise<void> {
  drawerLoading.value = true
  drawerError.value = ''
  try {
    current.value = await docApi.documentDetail(id)
    try {
      const page = await docApi.documentVersions(id, { pageNum: 1, pageSize: 20 })
      versions.value = page.list
    } catch {
      versions.value = []
    }
  } catch (e) {
    drawerError.value = e instanceof ApiError ? e.message : '加载失败'
    current.value = null
    versions.value = []
  } finally {
    drawerLoading.value = false
  }
}

/* ---------------- 归档 / 恢复上架 / 回收站 / 彻底删除 ---------------- */

const archiveOpen = ref(false)
const archiveRemark = ref('')
const archiveError = ref('')

const destroyOpen = ref(false)
const destroyConfirmText = ref('')
const destroyError = ref('')

async function submitArchive(): Promise<void> {
  archiveError.value = ''
  const remark = archiveRemark.value.trim()
  if (!remark) {
    archiveError.value = '归档意见不能为空'
    return
  }
  const doc = current.value
  if (!doc) return
  try {
    await docApi.archiveDocument(doc.id, { remark })
    archiveOpen.value = false
    archiveRemark.value = ''
    ui.ok('已归档（仍可检索，只读）')
    await refreshAfterAction()
  } catch (e) {
    archiveError.value = e instanceof ApiError ? e.message : '归档失败'
  }
}

async function republish(): Promise<void> {
  const doc = current.value
  if (!doc || busy.value) return
  busy.value = true
  try {
    await docApi.republishDocument(doc.id)
    ui.ok('已恢复上架（并清空了驳回理由）')
    await refreshAfterAction()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '恢复上架失败')
  } finally {
    busy.value = false
  }
}

async function moveToTrash(): Promise<void> {
  const doc = current.value
  if (!doc || busy.value) return
  busy.value = true
  try {
    await docApi.moveToTrash(doc.id)
    ui.ok('已移入回收站')
    await refreshAfterAction()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '移入回收站失败')
  } finally {
    busy.value = false
  }
}

async function submitDestroy(): Promise<void> {
  destroyError.value = ''
  const doc = current.value
  if (!doc) return
  if (destroyConfirmText.value !== doc.title) {
    destroyError.value = '请输入完整标题以确认（大小写与标点都要一致）'
    return
  }
  try {
    await docApi.destroyDocument(doc.id, { confirm: true })
    destroyOpen.value = false
    destroyConfirmText.value = ''
    ui.ok('已彻底删除（不可恢复）')
    current.value = null
    versions.value = []
    await load()
  } catch (e) {
    // 非回收站文档会收到 400「仅回收站中的文档可以彻底删除」——原样展示服务端文案
    destroyError.value = e instanceof ApiError ? e.message : '彻底删除失败'
  }
}

/** 动作做完同时刷新列表与抽屉（状态可能已变）。 */
async function refreshAfterAction(): Promise<void> {
  const id = current.value?.id
  await load()
  if (id) await open(id)
}

function onFilter(): void {
  pageNum.value = 1
  void load()
}

function clearFilters(): void {
  filters.status = ''
  filters.authorId = ''
  filters.keyword = ''
  filters.sort = 'updatedAt_desc'
  pageNum.value = 1
  current.value = null
  versions.value = []
  void load()
}

function onPage(page: number): void {
  pageNum.value = page
  void load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>内容治理</h1>
        <div class="desc">
          全平台文档（含回收站）。归档写审核意见、恢复上架清空驳回理由；彻底删除只能对回收站里的文档做，
          且要输入完整标题确认。
        </div>
      </div>
      <div class="acts">
        <button class="btn" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>

    <StateBlock v-if="noperm" kind="noperm" :desc="errorText">
      <RouterLink class="btn btn-sm" to="/workbench">回到工作台</RouterLink>
    </StateBlock>

    <template v-else>
      <div class="grid g4 mb16">
        <div class="stat">
          <div class="k">全部文档</div>
          <div class="v">{{ counts.all < 0 ? '—' : counts.all }}<small>篇</small></div>
          <div class="d">含草稿与回收站</div>
        </div>
        <div class="stat s2">
          <div class="k">草稿</div>
          <div class="v">{{ counts.DRAFT < 0 ? '—' : counts.DRAFT }}<small>篇</small></div>
          <div class="d">仅作者可见</div>
        </div>
        <div class="stat s4">
          <div class="k">已发布</div>
          <div class="v">{{ counts.PUBLISHED < 0 ? '—' : counts.PUBLISHED }}<small>篇</small></div>
          <div class="d">全校可检索</div>
        </div>
        <div class="stat s3">
          <div class="k">已归档 / 回收站</div>
          <div class="v">{{ counts.ARCHIVED < 0 ? '—' : counts.ARCHIVED }} / {{ counts.TRASH < 0 ? '—' : counts.TRASH }}</div>
          <div class="d">归档只读可检索；回收站待处理</div>
        </div>
      </div>

      <div class="card mb16">
        <div class="card-hd"><h3>筛选</h3><span class="sub">概览数字由各状态一次 count 查询得来</span></div>
        <div class="card-bd">
          <div class="filter">
            <div class="field">
              <label class="label">状态</label>
              <select v-model="filters.status" class="select" @change="onFilter">
                <option value="">全部</option>
                <option value="DRAFT">草稿</option>
                <option value="PUBLISHED">已发布</option>
                <option value="ARCHIVED">已归档</option>
                <option value="TRASH">回收站</option>
              </select>
            </div>
            <div class="field">
              <label class="label">拟稿人</label>
              <select v-model="filters.authorId" class="select" @change="onFilter">
                <option value="">全部</option>
                <option v-for="a in authors" :key="a.id" :value="a.id">{{ a.name }}</option>
              </select>
              <div class="hint">候选来自全量文档的作者（用户目录接口需 sys:user）</div>
            </div>
            <div class="field">
              <label class="label">关键词</label>
              <input v-model="filters.keyword" class="input" placeholder="标题 / 摘要" @keyup.enter="onFilter" />
            </div>
            <div class="field">
              <label class="label">排序</label>
              <select v-model="filters.sort" class="select" @change="onFilter">
                <option v-for="s in SORTS" :key="s.value" :value="s.value">{{ s.label }}</option>
              </select>
            </div>
            <div class="filter-acts">
              <button class="btn" @click="onFilter">查询</button>
              <button class="btn" @click="clearFilters">清空筛选</button>
            </div>
          </div>
        </div>
      </div>

      <StateBlock v-if="loading" kind="loading" :rows="6" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!list.length" kind="empty" title="没有匹配的文档" desc="换个筛选条件，或清空筛选看全部。">
        <button class="btn btn-sm" @click="clearFilters">清空筛选</button>
      </StateBlock>

      <div v-else class="grid g-drawer-right">
        <div class="card">
          <div class="card-hd">
            <h3>全平台文档</h3>
            <span class="sub">共 {{ total }} 篇（含回收站）</span>
          </div>
          <table class="tbl">
            <thead>
              <tr>
                <th>文档</th>
                <th class="w-fix">状态</th>
                <th class="w-fix2">拟稿人</th>
                <th class="w-fix2">更新时间</th>
                <th class="w-fix"></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="d in list" :key="d.id" :class="{ 'row-on': current?.id === d.id }" @click="open(d.id)">
                <td>
                  <div class="strong">{{ d.title }}</div>
                  <span class="sub">{{ d.categoryName ?? '未分类' }} · v{{ d.versionNum }}</span>
                </td>
                <td><StatusBadge :status="d.status" /></td>
                <td class="sm t2">{{ d.authorName ?? '—' }}</td>
                <td class="sm t2">{{ relativeTime(d.updatedAt) }}</td>
                <td class="acts"><RouterLink class="link" :to="`/docs/${d.id}`">查看</RouterLink></td>
              </tr>
            </tbody>
          </table>
          <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="onPage" />
        </div>

        <div class="drawer">
          <div class="drawer-hd">
            <h3>{{ current?.title ?? '选择左侧文档查看' }}</h3>
            <span class="spacer"></span>
            <StatusBadge v-if="current" :status="current.status" />
          </div>

          <StateBlock v-if="drawerLoading" kind="loading" :rows="5" />
          <StateBlock v-else-if="drawerError" kind="error" :desc="drawerError" />
          <StateBlock
            v-else-if="!current"
            kind="empty"
            title="还没有选中文档"
            desc="点左侧任意一行，这里显示元信息与版本历史。"
          />

          <template v-else>
            <div class="drawer-bd">
              <div class="kv mb12">
                <div class="k">拟稿人</div><div>{{ current.authorName ?? '—' }}</div>
                <div class="k">分类</div><div>{{ current.categoryName ?? '未分类' }}</div>
                <div class="k">状态</div><div><StatusBadge :status="current.status" /></div>
                <div class="k">版本</div><div>v{{ current.versionNum }}</div>
                <div class="k">创建时间</div><div>{{ formatDateTime(current.createdAt) }}</div>
                <div class="k">更新时间</div><div>{{ formatDateTime(current.updatedAt) }}</div>
              </div>

              <div v-if="current.rejectReason" class="alert a-danger mb12">
                <span class="ico">!</span><span>驳回理由：{{ current.rejectReason }}</span>
              </div>

              <div class="sec-title">版本历史</div>
              <table v-if="versions.length" class="tbl mb12">
                <thead>
                  <tr>
                    <th class="w-fix">版本</th>
                    <th class="w-fix2">类型</th>
                    <th>说明</th>
                    <th class="w-fix2">操作人</th>
                  </tr>
                </thead>
                <tbody>
                  <tr v-for="v in versions" :key="v.id">
                    <td>v{{ v.versionNum }}</td>
                    <td class="sm t2">{{ CHANGE_TYPE_TEXT[v.changeType] ?? v.changeType }}</td>
                    <td class="sm t2">{{ v.changeRemark ?? '—' }}</td>
                    <td class="sm t2">{{ v.operatorName ?? '—' }}</td>
                  </tr>
                </tbody>
              </table>
              <StateBlock v-else kind="empty" title="暂无版本记录" desc="这篇文档还没有产生版本留痕。" />

              <RouterLink class="btn btn-sm" :to="`/docs/${current.id}`">打开完整详情页</RouterLink>
            </div>

            <div class="drawer-ft">
              <span v-if="!canArchive && !canDelete" class="xs t3">
                你没有归档与删除权限（doc:archive / doc:delete）
              </span>
              <template v-else>
                <span class="spacer"></span>
                <button v-if="canArchive && current.status === 'PUBLISHED'" class="btn" @click="archiveOpen = true">
                  归档
                </button>
                <button
                  v-if="canArchive && current.status === 'ARCHIVED'"
                  class="btn"
                  :disabled="busy"
                  @click="republish"
                >
                  恢复上架
                </button>
                <button
                  v-if="canDelete && current.status !== 'TRASH'"
                  class="btn"
                  :disabled="busy"
                  @click="moveToTrash"
                >
                  移入回收站
                </button>
                <button
                  v-if="canDelete && current.status === 'TRASH'"
                  class="btn btn-danger"
                  @click="destroyOpen = true"
                >
                  彻底删除
                </button>
              </template>
            </div>
          </template>
        </div>
      </div>
    </template>

    <!-- 归档：写审核意见 -->
    <div v-if="archiveOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">归档文档</div>
        <div class="modal-bd">
          <div class="field">
            <label class="label">归档意见 <span class="req">*</span></label>
            <textarea v-model="archiveRemark" class="textarea" rows="3" placeholder="例如：文件已过有效期，转入归档"></textarea>
            <div class="hint">归档后仍可检索，但正文只读</div>
            <div v-if="archiveError" class="hint err-text">{{ archiveError }}</div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="archiveOpen = false">取消</button>
          <button class="btn btn-primary" @click="submitArchive">确认归档</button>
        </div>
      </div>
    </div>

    <!-- 彻底删除：必须输入完整标题（BR-08） -->
    <div v-if="destroyOpen && current" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">彻底删除（不可恢复）</div>
        <div class="modal-bd">
          <p>
            即将彻底删除《{{ current.title }}》。数据库里的行会被删除，版本留痕一并消失，<b>无法恢复</b>。
          </p>
          <div class="field mt12">
            <label class="label">输入完整标题以确认</label>
            <input v-model="destroyConfirmText" class="input" :placeholder="current.title" />
            <div v-if="destroyError" class="hint err-text">{{ destroyError }}</div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="destroyOpen = false">取消</button>
          <button class="btn btn-danger" :disabled="destroyConfirmText !== current.title" @click="submitDestroy">
            确认彻底删除
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
