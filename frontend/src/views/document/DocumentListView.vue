<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import Pager from '@/components/Pager.vue'
import StateBlock from '@/components/StateBlock.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import * as taxApi from '@/api/taxonomy'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { MATCHED_IN_TEXT, relativeTime } from '@/utils/format'
import type { CategoryVo, DocumentSort, DocumentVo, TagVo } from '@/types'

/**
 * 文档检索（§8.4）：关键词（≥2 字走全文检索、能命中正文）/ 分类 / 标签 / 排序。
 * 出参里的 highlight（含 <em>）与 matchedIn 只在这一分支返回，需要渲染出来让用户看到"命中在哪"。
 */
const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const user = useUserStore()

const keyword = ref<string>(typeof route.query.keyword === 'string' ? route.query.keyword : '')
const categoryId = ref<string>(typeof route.query.categoryId === 'string' ? route.query.categoryId : '')
const selectedTags = ref<string[]>([])
const sort = ref<DocumentSort>('updatedAt_desc')

const list = ref<DocumentVo[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const loading = ref(false)
const errorText = ref('')

const categories = ref<CategoryVo[]>([])
const tags = ref<TagVo[]>([])

/** 关键词是否够走全文检索（后端约定 ≥2 字）。 */
const fullTextMode = computed(() => keyword.value.trim().length >= 2)
const canUseRelevance = computed(() => fullTextMode.value)

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

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  try {
    const page = await docApi.searchDocuments({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value.trim() || undefined,
      categoryId: categoryId.value || undefined,
      tagIds: selectedTags.value.length ? selectedTags.value : undefined,
      sort: sort.value
    })
    list.value = page.list
    total.value = page.total
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '检索失败，请稍后重试'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
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

function toggleTag(id: string): void {
  const idx = selectedTags.value.indexOf(id)
  if (idx >= 0) selectedTags.value.splice(idx, 1)
  else selectedTags.value.push(id)
}

function resetFilters(): void {
  keyword.value = ''
  categoryId.value = ''
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
          只检索<strong>已发布</strong>文档。关键词 <strong>≥ 2 字</strong>时走全文检索（能命中正文，并给出命中片段）；
          不足 2 字回落标题/摘要模糊匹配。
        </div>
      </div>
      <div class="acts">
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
        <span v-if="fullTextMode" class="badge primary">全文检索</span>
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

      <StateBlock v-if="loading" kind="loading" :rows="6" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!list.length" kind="empty" title="没有匹配的文档" desc="换个关键词，或清空筛选条件。">
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
              <th class="w-fix2">更新时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="d in list" :key="d.id">
              <td>
                <RouterLink class="title" :to="`/docs/${d.id}`">{{ d.title }}</RouterLink>
                <span v-if="d.summary" class="sub">{{ d.summary }}</span>
                <!-- 命中片段：后端已用 <em> 包裹命中词，且只给"真的有命中"的片段 -->
                <span v-if="d.highlight" class="hl" v-html="d.highlight"></span>
                <span v-if="d.matchedIn" class="hit-note">
                  命中位置：{{ MATCHED_IN_TEXT[d.matchedIn] ?? d.matchedIn }}
                </span>
              </td>
              <td class="sm t2">{{ d.categoryName ?? '未分类' }}</td>
              <td class="sm t2">{{ d.authorName ?? '—' }}</td>
              <td class="t2">v{{ d.versionNum }}</td>
              <td class="num t2">{{ d.viewCount }}</td>
              <td class="sm t2">{{ relativeTime(d.updatedAt) }}</td>
            </tr>
          </tbody>
        </table>
        <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="onPageChange" />
      </template>
    </div>
  </div>
</template>
