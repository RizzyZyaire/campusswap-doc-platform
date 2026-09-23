<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { CHANGE_TYPE_TEXT, formatDateTime, formatPrice, relativeTime } from '@/utils/format'
import { extractHeadingsFromHtml, renderMarkdown } from '@/utils/markdown'
import type { DocumentDetailVo, DocumentVersionVo, DocumentVo } from '@/types'

/**
 * 文档详情（UI_UX_SPECIFICATION §8.4）。
 *
 * 面包屑 → 标题 + 状态徽标 + 版本 + 元信息 + 标签 → 操作条 → 左正文 / 右粘性侧栏
 * （本文目录 · 文档信息 · 相关文档）→ 底部版本历史表。
 * 回收站文档对作者与管理员可见（后端已放行），此时只能「恢复」或「彻底删除」。
 */
const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const user = useUserStore()

const id = computed(() => String(route.params.id ?? ''))
const doc = ref<DocumentDetailVo | null>(null)
const versions = ref<DocumentVersionVo[]>([])
const related = ref<DocumentVo[]>([])
const loading = ref(true)
const errorText = ref('')
const noperm = ref(false)
const versionDenied = ref(false)
const busy = ref(false)

const bodyHtml = computed(() => renderMarkdown(doc.value?.contentMd))
/**
 * 本文目录（从渲染结果里取，编号与标题 id 天生一致）。
 * 正文第一行常常就是 `# 标题`，与页面大标题重复 —— 这种一级标题不进目录。
 */
const toc = computed(() => {
  const items = extractHeadingsFromHtml(bodyHtml.value)
  const title = (doc.value?.title ?? '').trim()
  return items.filter((h, i) => !(i === 0 && h.level === 1 && h.text === title))
})
const isTrash = computed(() => doc.value?.status === 'TRASH')

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  noperm.value = false
  versionDenied.value = false
  try {
    const detail = await docApi.documentDetail(id.value)
    doc.value = detail
    // 版本历史只对作者本人与文档管理员开放（后端是 assertOwnerOrManage，权限码 doc:mine 只是入口门槛），
    // 所以先判断再请求，不拿一个必然 403 的请求当"空数据"。
    if (detail.canEdit || user.hasPerm(PERM.docManage)) {
      try {
        const page = await docApi.documentVersions(id.value, { pageNum: 1, pageSize: 20 })
        versions.value = page.list
      } catch {
        versions.value = []
        versionDenied.value = true
      }
    } else {
      versions.value = []
      versionDenied.value = true
    }
    void loadRelated(detail)
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

/**
 * 相关文档：同分类里最新的几篇（排除自己）。
 *
 * <p>后端没有"相关推荐"接口，这里用现成的检索接口按分类取 5 条 —— 是真实数据，
 * 但语义只是"同分类最新"，不假装是相似度推荐。</p>
 */
async function loadRelated(detail: DocumentDetailVo): Promise<void> {
  if (!detail.categoryId || detail.status !== 'PUBLISHED') {
    related.value = []
    return
  }
  try {
    const page = await docApi.searchDocuments({ categoryId: detail.categoryId, pageNum: 1, pageSize: 6 })
    related.value = page.list.filter((d) => d.id !== detail.id).slice(0, 5)
  } catch {
    related.value = []
  }
}

async function toggleFavorite(): Promise<void> {
  const current = doc.value
  if (!current || busy.value) return
  busy.value = true
  try {
    const res = current.favorited ? await docApi.unfavorite(current.id) : await docApi.favorite(current.id)
    current.favorited = res.favorited
    current.favoriteCount = res.favoriteCount
    ui.ok(res.favorited ? '已收藏' : '已取消收藏')
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '操作失败')
  } finally {
    busy.value = false
  }
}

async function publish(): Promise<void> {
  if (!doc.value) return
  try {
    doc.value = await docApi.publishDocument(doc.value.id)
    ui.ok('已提交发布，等待文档管理员审核')
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '发布失败')
  }
}

async function derive(): Promise<void> {
  if (!doc.value) return
  try {
    const created = await docApi.deriveDocument(doc.value.id, { title: null })
    ui.ok('已派生为新草稿')
    await router.push({ name: 'docs-edit', params: { id: created.id } })
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '派生失败')
  }
}

async function archive(): Promise<void> {
  if (!doc.value) return
  try {
    doc.value = await docApi.archiveDocument(doc.value.id, { remark: '在详情页归档' })
    ui.ok('已归档（仍可检索，只读）')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '归档失败')
  }
}

async function republish(): Promise<void> {
  if (!doc.value) return
  try {
    doc.value = await docApi.republishDocument(doc.value.id)
    ui.ok('已恢复上架')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '恢复上架失败')
  }
}

async function restore(): Promise<void> {
  if (!doc.value) return
  try {
    doc.value = await docApi.restoreDocument(doc.value.id)
    ui.ok('已从回收站恢复为草稿')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '恢复失败')
  }
}

async function moveToTrash(): Promise<void> {
  if (!doc.value) return
  try {
    await docApi.moveToTrash(doc.value.id)
    ui.ok('已移入回收站')
    await load()
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <div>
    <StateBlock v-if="loading" kind="loading" :rows="8" />
    <StateBlock v-else-if="noperm" kind="noperm" :desc="errorText">
      <RouterLink class="btn btn-sm" to="/docs">回到检索</RouterLink>
    </StateBlock>
    <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
      <button class="btn btn-sm" @click="load">重新加载</button>
    </StateBlock>

    <template v-else-if="doc">
      <div class="crumb mb12">
        <RouterLink class="link" to="/docs">文档检索</RouterLink> / {{ doc.categoryName ?? '未分类' }}
      </div>

      <div class="page-head">
        <div>
          <div class="row wrap mb8">
            <h1>{{ doc.title }}</h1>
            <StatusBadge :status="doc.status" />
            <span class="badge plain">v{{ doc.versionNum }}</span>
          </div>
          <div class="row wrap xs t2">
            <span>拟稿人：{{ doc.authorName ?? '—' }}</span><span>·</span>
            <span>分类：{{ doc.categoryName ?? '未分类' }}</span><span>·</span>
            <span>更新时间：{{ formatDateTime(doc.updatedAt) }}</span><span>·</span>
            <span>阅读 {{ doc.viewCount }}</span><span>·</span>
            <span>收藏 {{ doc.favoriteCount }}</span>
            <span v-if="doc.priceCents">·</span>
            <span v-if="doc.priceCents">{{ formatPrice(doc.priceCents) }}</span>
          </div>
          <div v-if="doc.tags.length" class="row wrap mt8">
            <span v-for="t in doc.tags" :key="t.id" class="tag">{{ t.name }}</span>
          </div>
        </div>
        <div class="acts">
          <button v-if="user.hasPerm(PERM.docFavorite)" class="btn" :disabled="busy" @click="toggleFavorite">
            {{ doc.favorited ? '已收藏' : '收藏' }}
          </button>
          <button v-if="user.hasPerm(PERM.docDerive) && !isTrash" class="btn" @click="derive">派生</button>
          <RouterLink v-if="doc.canEdit && !isTrash" class="btn btn-primary" :to="`/docs/edit/${doc.id}`">编辑</RouterLink>
          <a class="btn" href="#versions">版本历史</a>
        </div>
      </div>

      <div v-if="isTrash" class="alert a-warn mb16">
        <span class="ico">!</span>
        <span>这篇文档在回收站中，正文只读。可恢复为草稿，或彻底删除（不可恢复）。</span>
      </div>

      <div v-if="doc.rejectReason" class="alert a-danger mb16">
        <span class="ico">!</span><span>审核未通过：{{ doc.rejectReason }}</span>
      </div>

      <div class="grid g-side">
        <div>
          <div class="card">
            <div class="card-bd">
              <div v-if="bodyHtml" class="md" v-html="bodyHtml"></div>
              <StateBlock v-else kind="empty" title="这篇文档还没有正文" desc="作者尚未填写 Markdown 正文。">
                <RouterLink v-if="doc.canEdit" class="btn btn-sm btn-primary" :to="`/docs/edit/${doc.id}`">去编辑</RouterLink>
              </StateBlock>
            </div>
          </div>

          <div id="versions" class="card">
            <div class="card-hd">
              <h3>版本历史</h3>
              <span class="sub">
                <template v-if="versionDenied">仅作者本人与文档管理员可见</template>
                <template v-else>每次操作自动留痕 · 共 {{ versions.length }} 条</template>
              </span>
            </div>
            <table v-if="versions.length" class="tbl">
              <thead>
                <tr>
                  <th class="w-fix">版本</th>
                  <th class="w-fix2">变更类型</th>
                  <th>说明</th>
                  <th class="w-fix2">操作人</th>
                  <th class="w-fix2">时间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="v in versions" :key="v.id">
                  <td>v{{ v.versionNum }}</td>
                  <td><span class="badge plain">{{ CHANGE_TYPE_TEXT[v.changeType] ?? v.changeType }}</span></td>
                  <td class="sm t2">{{ v.changeRemark ?? '—' }}</td>
                  <td class="sm t2">{{ v.operatorName ?? '—' }}</td>
                  <td class="sm t3">{{ relativeTime(v.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
            <StateBlock v-else kind="noperm" title="暂无版本记录" desc="这篇文档还没有产生版本留痕，或你无权查看。" />
          </div>
        </div>

        <div class="side-sticky">
          <div v-if="toc.length" class="card">
            <div class="card-hd"><h3>本文目录</h3></div>
            <div class="card-bd">
              <div class="toc">
                <a v-for="h in toc" :key="h.id" :href="`#${h.id}`" :class="{ lv3: h.level === 3, lv2: h.level === 2 }">
                  {{ h.text }}
                </a>
              </div>
            </div>
          </div>

          <div class="card">
            <div class="card-hd"><h3>文档信息</h3></div>
            <div class="card-bd">
              <div class="kv">
                <div class="k">拟稿人</div><div>{{ doc.authorName ?? '—' }}</div>
                <div class="k">分类</div><div>{{ doc.categoryName ?? '未分类' }}</div>
                <div class="k">状态</div><div><StatusBadge :status="doc.status" /></div>
                <div class="k">版本</div><div>v{{ doc.versionNum }}</div>
                <div class="k">价格标记</div><div>{{ formatPrice(doc.priceCents) }}</div>
                <div class="k">阅读 / 收藏</div><div>{{ doc.viewCount }} / {{ doc.favoriteCount }}</div>
                <div class="k">创建时间</div><div>{{ formatDateTime(doc.createdAt) }}</div>
                <div class="k">更新时间</div><div>{{ formatDateTime(doc.updatedAt) }}</div>
                <template v-if="doc.derivedFromId">
                  <div class="k">派生自</div>
                  <div><RouterLink class="link" :to="`/docs/${doc.derivedFromId}`">源文档 #{{ doc.derivedFromId }}</RouterLink></div>
                </template>
              </div>
            </div>
          </div>

          <div v-if="related.length" class="card">
            <div class="card-hd"><h3>相关文档</h3><span class="sub">同分类最新</span></div>
            <div class="card-bd tight">
              <RouterLink v-for="r in related" :key="r.id" class="row navrow" :to="`/docs/${r.id}`">
                <span class="sm">{{ r.title }}</span>
                <span class="xs t3">{{ relativeTime(r.updatedAt) }}</span>
              </RouterLink>
            </div>
          </div>

          <div class="card">
            <div class="card-hd"><h3>可执行的操作</h3><span class="sub">按权限与状态显示</span></div>
            <div class="card-bd">
              <div class="row wrap">
                <button
                  v-if="doc.status === 'DRAFT' && user.hasPerm(PERM.docPublish)"
                  class="btn btn-sm btn-primary"
                  @click="publish"
                >
                  提交发布
                </button>
                <button
                  v-if="doc.status === 'PUBLISHED' && user.hasPerm(PERM.docArchive)"
                  class="btn btn-sm"
                  @click="archive"
                >
                  归档
                </button>
                <button
                  v-if="doc.status === 'ARCHIVED' && user.hasPerm(PERM.docArchive)"
                  class="btn btn-sm"
                  @click="republish"
                >
                  恢复上架
                </button>
                <button v-if="isTrash && user.hasPerm(PERM.docRestore)" class="btn btn-sm" @click="restore">
                  从回收站恢复
                </button>
                <button v-if="!isTrash && user.hasPerm(PERM.docDelete)" class="btn btn-sm btn-danger" @click="moveToTrash">
                  移入回收站
                </button>
                <span
                  v-if="!user.hasPerm(PERM.docPublish) && !doc.canEdit && !user.hasPerm(PERM.docDelete)"
                  class="xs t3"
                >
                  当前身份对这篇文档只有阅读权限。
                </span>
              </div>
              <div class="xs t3 mt12">
                「下架」是权限树里的预留位，后端没有对应接口，因此界面不提供入口（UI 规格 §10.6）：
                归档已经覆盖"下架但可检索只读"的语义。
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
