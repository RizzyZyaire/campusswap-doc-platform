<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import Pager from '@/components/Pager.vue'
import StateBlock from '@/components/StateBlock.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import * as searchApi from '@/api/search'
import * as taxApi from '@/api/taxonomy'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { relativeTime } from '@/utils/format'
import type { CategoryVo, DocumentSort, DocumentVo, TagVo } from '@/types'
import { SEARCH_SCOPE_TEXT, type SearchEngineStatusVo, type SearchRebuildTask, type SearchScope } from '@/types/search'

/**
 * 文档检索（§8.4 + 课件 10.1 ES 全文检索）。
 *
 * <p>两条链路，一个界面：</p>
 * <ul>
 *   <li><b>有关键词且没选标签</b> → 走 {@code GET /api/search/documents}（ES + IK 分词，可切换
 *       "标题 / 正文 / 标题+摘要+正文"三种检索范围，结果带 {@code <mark>} 高亮与相关度分数；
 *       ES 没启动时后端自动降级为 MySQL 引擎并在页面上标出来）；</li>
 *   <li><b>没关键词，或选了标签</b> → 走既有的 {@code GET /api/documents}（分类/标签/排序筛选，
 *       标签是 AND 命中，由 MySQL 引擎处理）。</li>
 * </ul>
 *
 * <p>高亮片段由后端转义后再插标记，前端只白名单渲染 {@code <mark>}（见后端 SearchTextHighlighter）。</p>
 */
const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const user = useUserStore()

const keyword = ref<string>(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const categoryId = ref<string>(typeof route.query.categoryId === 'string' ? route.query.categoryId : '')
const scope = ref<SearchScope>('ALL')
const selectedTags = ref<string[]>([])
const sort = ref<DocumentSort>('updatedAt_desc')

/** 统一行结构：两条链路的结果都归一到这里，模板只写一次。 */
interface Row {
  id: string
  title: string
  titleHtml: string
  snippetHtml: string
  categoryName: string | null
  authorName: string | null
  updatedAt: string | null
  versionNum: number | null
  viewCount: number | null
  matched: string[]
  score: string | null
}

const rows = ref<Row[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const errorText = ref('')
/** 本次结果由哪个引擎给出（来自后端回执，不靠前端猜）。 */
const engineName = ref<string>('')
const engineMessage = ref<string>('')
const indexStatus = ref<SearchEngineStatusVo | null>(null)
/** 当前重建任务（提交后轮询更新，界面显示 已提交→执行中→已完成）。 */
const rebuildTask = ref<SearchRebuildTask | null>(null)
/** 是否正在处理重建（防重复点击）。 */
const rebuildBusy = ref(false)

const categories = ref<CategoryVo[]>([])
const tags = ref<TagVo[]>([])

const KEYWORD_MIN = 2
/** 关键词是否够走全文检索（后端约定 ≥2 字）。 */
const fullTextMode = computed(() => keyword.value.trim().length >= KEYWORD_MIN)
/** 是否走 ES 检索链路（有标签筛选时交给 MySQL，标签是 AND 命中）。 */
const useFullTextApi = computed(() => fullTextMode.value && selectedTags.value.length === 0)
const canUseRelevance = computed(() => fullTextMode.value)

/** 命中字段的中文名。 */
const MATCHED_TEXT: Record<string, string> = { title: '标题', summary: '摘要', content: '正文' }

/** 检索范围选项（模板里迭代它，避免在模板内写类型断言）。 */
const SCOPES: SearchScope[] = ['TITLE', 'CONTENT', 'ALL']

const flatCategories = computed(() => {
  const out: CategoryVo[] = []
  const walk = (nodes: CategoryVo[], depth: number): void => {
    for (const n of nodes) {
      out.push({ ...n, name: `${'　'.repeat(depth)}${depth ? '└ ' : ''}${n.name}` })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out
})

/** 把后端给的 <em> 命中标记换成界面统一使用的 <mark>（两条链路渲染同一套样式）。 */
function toMark(html: string | null | undefined): string {
  if (!html) return ''
  return html.replace(/<em>/g, '<mark>').replace(/<\/em>/g, '</mark>')
}

/** 既有 DocumentVo → 统一行结构。 */
function fromDocumentVo(d: DocumentVo): Row {
  return {
    id: d.id,
    title: d.title,
    titleHtml: toMark(d.title),
    snippetHtml: toMark(d.highlight) || toMark(d.summary),
    categoryName: d.categoryName ?? null,
    authorName: d.authorName ?? null,
    updatedAt: d.updatedAt ?? null,
    versionNum: d.versionNum ?? null,
    viewCount: d.viewCount ?? null,
    matched: d.matchedIn ? [d.matchedIn] : [],
    score: null
  }
}

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    if (useFullTextApi.value) {
      const res = await searchApi.searchFullText({
        keyword: keyword.value.trim(),
        scope: scope.value,
        categoryId: categoryId.value || undefined,
        sort: sort.value,
        pageNum: pageNum.value,
        pageSize: pageSize.value
      })
      engineName.value = res.engine
      engineMessage.value = res.engineMessage
      rows.value = res.list.map((h) => ({
        id: h.id,
        title: h.title,
        titleHtml: h.titleHighlight || toMark(h.title),
        snippetHtml: h.snippet,
        categoryName: h.categoryName,
        authorName: h.authorName,
        updatedAt: h.publishAt,
        versionNum: null,
        viewCount: null,
        matched: h.matchedFields ?? [],
        score: h.score
      }))
      total.value = res.total
    } else {
      const page = await docApi.searchDocuments({
        pageNum: pageNum.value,
        pageSize: pageSize.value,
        keyword: keyword.value.trim() || undefined,
        categoryId: categoryId.value || undefined,
        tagIds: selectedTags.value.length ? selectedTags.value : undefined,
        sort: sort.value
      })
      engineName.value = 'mysql'
      engineMessage.value = selectedTags.value.length
        ? '标签为 AND 命中，由 MySQL 引擎处理筛选'
        : '未输入关键词，直接列出最近更新的已发布文档'
      rows.value = page.list.map(fromDocumentVo)
      total.value = page.total
    }
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '检索失败，请稍后重试'
    rows.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 拉一次引擎状态（页头徽标：ES 已连接 / 已降级）。 */
async function loadEngineStatus(): Promise<void> {
  try {
    indexStatus.value = await searchApi.searchEngineStatus()
  } catch {
    indexStatus.value = null
  }
}

/**
 * 提交全量重建任务并轮询进度（课件 13.1：走 RabbitMQ 异步）。
 *
 * 接口只负责"下单"（服务端实测 3~17ms 返回 SUBMITTED + taskId），真正的重建在后台消费者里跑；
 * 这里每 800ms 查一次任务状态，界面能完整看到 已提交 → 执行中 → 已完成 的过程。
 */
async function rebuildIndex(): Promise<void> {
  if (rebuildBusy.value) return
  rebuildBusy.value = true
  try {
    const task = await searchApi.submitRebuild()
    rebuildTask.value = task
    if (task.status === 'FAILED') {
      ui.err(task.message || '任务提交失败')
      return
    }
    ui.ok(`重建任务已提交（${task.submitCostMs ?? 0} ms），后台消费者执行中…`)
    for (let i = 0; i < 60; i += 1) {
      await new Promise((r) => setTimeout(r, 800))
      const latest = await searchApi.getRebuildTask(task.taskId)
      rebuildTask.value = latest
      if (latest.status === 'SUCCEEDED') {
        ui.ok(
          `索引重建完成：同步 ${latest.indexed} 篇，索引内 ${latest.indexCount} 篇，用时 ${latest.elapsedMs} ms`
        )
        await loadEngineStatus()
        await load()
        return
      }
      if (latest.status === 'FAILED') {
        ui.err(latest.message || '索引重建失败（消息已进死信队列，可查 RabbitMQ 控制台）')
        await loadEngineStatus()
        return
      }
    }
    ui.warn('任务仍在后台执行，可稍后回本页查看状态')
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '提交重建任务失败')
  } finally {
    rebuildBusy.value = false
  }
}

function applyFilters(): void {
  pageNum.value = 1
  // 把筛选条件同步到地址栏：刷新/分享都能复现同一份结果
  void router.replace({
    name: 'docs',
    query: {
      ...(keyword.value.trim() ? { keyword: keyword.value.trim() } : {}),
      ...(categoryId.value ? { categoryId: categoryId.value } : {})
    }
  })
  void load()
}

/** 切换检索范围后立即重查（这是用户明确要的"搜索前先选范围"）。 */
function chooseScope(next: SearchScope): void {
  if (scope.value === next) return
  scope.value = next
  if (fullTextMode.value) {
    pageNum.value = 1
    void load()
  }
}

function toggleTag(id: string): void {
  const idx = selectedTags.value.indexOf(id)
  if (idx >= 0) selectedTags.value.splice(idx, 1)
  else selectedTags.value.push(id)
}

function resetFilters(): void {
  keyword.value = ''
  categoryId.value = ''
  scope.value = 'ALL'
  selectedTags.value = []
  sort.value = 'updatedAt_desc'
  applyFilters()
}

function onPageChange(page: number): void {
  pageNum.value = page
  void load()
}

// 排序用 relevance 时必须有 ≥2 字关键词，否则后端 400 —— 这里主动兜住，避免用户看到报错
watch(canUseRelevance, (ok) => {
  if (!ok && sort.value === 'relevance') {
    sort.value = 'updatedAt_desc'
    ui.warn('相关度排序仅在关键词 ≥2 字（全文检索）时可用，已切回按更新时间')
  }
})

onMounted(async () => {
  await Promise.all([
    load(),
    loadEngineStatus(),
    taxApi.categoryTree().then((t) => (categories.value = t)).catch(() => (categories.value = [])),
    taxApi
      .tagList({ pageNum: 1, pageSize: 50 })
      .then((p) => (tags.value = p.list))
      .catch(() => (tags.value = []))
  ])
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>文档检索</h1>
        <div class="desc">
          只检索<strong>已发布</strong>文档。关键词 <strong>≥ 2 字</strong>时走全文检索（Elasticsearch + IK 中文分词，
          可命中正文并高亮关键词）；不足 2 字回落标题/摘要模糊匹配。
        </div>
      </div>
      <div class="acts">
        <button v-if="user.hasPerm(PERM.docManage)" class="btn btn-sm" :disabled="rebuildBusy" @click="rebuildIndex">
          {{ rebuildBusy ? '重建中…' : '重建检索索引' }}
        </button>
        <RouterLink v-if="user.hasPerm(PERM.docCreate)" class="btn btn-primary" to="/docs/edit">新建文档</RouterLink>
      </div>
    </div>

    <div class="card">
      <div class="toolbar">
        <input
          v-model="keyword"
          class="input grow"
          placeholder="搜索标题或正文（≥2 字可搜正文）"
          @keyup.enter="applyFilters"
        />
        <select v-model="categoryId" class="select select-compact">
          <option value="">全部分类</option>
          <option v-for="c in flatCategories" :key="c.id" :value="c.id">{{ c.name }}</option>
        </select>
        <select v-model="sort" class="select select-compact">
          <option value="updatedAt_desc">最近更新 ↓</option>
          <option value="publishAt_desc">发布时间 ↓</option>
          <option value="viewCount_desc">阅读量 ↓</option>
          <option v-if="canUseRelevance" value="relevance">相关度 ↓（全文检索时）</option>
        </select>
        <button class="btn btn-sm btn-primary" @click="applyFilters">查询</button>
        <button class="btn btn-sm" @click="resetFilters">重置</button>
      </div>

      <!-- 检索范围：搜索前先选"在哪儿找"，用户明确要求的能力 -->
      <div class="card-bd tight">
        <div class="row wrap">
          <span class="xs t3">检索范围：</span>
          <button
            v-for="s in SCOPES"
            :key="s"
            class="tag"
            :class="{ on: scope === s }"
            :title="`在${SEARCH_SCOPE_TEXT[s]}中检索`"
            @click="chooseScope(s)"
          >
            {{ SEARCH_SCOPE_TEXT[s] }}
          </button>
          <span v-if="fullTextMode" class="badge primary">全文检索</span>
          <span v-if="!fullTextMode && keyword.trim()" class="xs t3">关键词不足 2 字：按标题/摘要模糊匹配</span>
        </div>
      </div>

      <div v-if="tags.length" class="card-bd tight">
        <div class="row wrap">
          <span class="xs t3">标签（AND 命中）：</span>
          <button
            v-for="t in tags"
            :key="t.id"
            class="tag"
            :class="{ on: selectedTags.includes(t.id) }"
            @click="toggleTag(t.id)"
          >
            {{ t.name }} <span class="t3">{{ t.useCount }}</span>
          </button>
          <button v-if="selectedTags.length" class="link xs" @click="selectedTags = []; applyFilters()">清空标签</button>
        </div>
      </div>

      <!-- 引擎徽标：把"这次是谁在检索"如实告诉用户（ES 未启动时后端会降级） -->
      <div v-if="engineName" class="card-bd tight">
        <div class="row wrap">
          <span class="badge" :class="engineName === 'elasticsearch' ? 'primary' : ''">
            检索引擎：{{ engineName === 'elasticsearch' ? 'Elasticsearch（IK 分词）' : 'MySQL' }}
          </span>
          <span class="xs t3">{{ engineMessage }}</span>
          <span v-if="indexStatus && indexStatus.elasticsearchAvailable" class="xs t3">
            索引文档数：{{ indexStatus.indexCount }}
          </span>
          <!-- 异步重建任务状态（课件 13.1：RabbitMQ 后台消费，前端轮询） -->
          <span
            v-if="rebuildTask"
            class="badge"
            :class="rebuildTask.status === 'SUCCEEDED' ? 'primary' : rebuildTask.status === 'FAILED' ? 'danger' : ''"
          >
            重建任务：{{ rebuildTask.statusLabel }}
            <template v-if="rebuildTask.indexed"> · {{ rebuildTask.indexed }} 篇</template>
          </span>
          <span v-if="rebuildTask" class="xs t3">taskId {{ rebuildTask.taskId.slice(0, 8) }}…</span>
        </div>
      </div>

      <StateBlock v-if="loading" kind="loading" :rows="6" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!rows.length" kind="empty" title="没有匹配的文档" desc="换个关键词，或清空筛选条件。">
        <button class="btn btn-sm" @click="resetFilters">清空筛选</button>
      </StateBlock>

      <template v-else>
        <table class="tbl">
          <thead>
            <tr>
              <th>文档</th>
              <th class="w-fix2">分类</th>
              <th class="w-fix2">拟稿人</th>
              <th class="w-fix">版本</th>
              <th class="w-fix">阅读</th>
              <th class="w-fix2">时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in rows" :key="d.id">
              <td>
                <!-- 高亮标题：后端已转义，只插 <mark> -->
                <RouterLink class="title" :to="`/docs/${d.id}`">
                  <span v-html="d.titleHtml"></span>
                </RouterLink>
                <!-- 命中片段：关键词在正文/摘要里的上下文，命中词已高亮 -->
                <span v-if="d.snippetHtml" class="hl" v-html="d.snippetHtml"></span>
                <span v-if="d.matched.length" class="hit-note">
                  命中位置：{{ d.matched.map((m) => MATCHED_TEXT[m] ?? m).join('、') }}
                  <template v-if="d.score !== null"> · 相关度 {{ d.score }}</template>
                </span>
              </td>
              <td class="sm t2">{{ d.categoryName ?? '未分类' }}</td>
              <td class="sm t2">{{ d.authorName ?? '—' }}</td>
              <td class="t2">{{ d.versionNum === null ? '—' : `v${d.versionNum}` }}</td>
              <td class="num t2">{{ d.viewCount ?? '—' }}</td>
              <td class="sm t2">{{ d.updatedAt ? relativeTime(d.updatedAt) : '—' }}</td>
            </tr>
          </tbody>
        </table>
        <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="onPageChange" />
      </template>
    </div>
  </div>
</template>
