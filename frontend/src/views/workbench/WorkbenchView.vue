<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import * as statApi from '@/api/files'
import * as reviewApi from '@/api/review'
import * as taxApi from '@/api/taxonomy'
import { PERM } from '@/utils/perm'
import { useUserStore } from '@/stores/user'
import { relativeTime } from '@/utils/format'
import type { CategoryVo, DocumentVo, StatVo } from '@/types'

/** 工作台（§8.2）：问候 + 4 张统计卡 + 我的草稿 + 待我审核 + 常用分类 + 通知。 */
const user = useUserStore()

const stats = ref<StatVo | null>(null)
const statError = ref('')
const drafts = ref<DocumentVo[]>([])
const draftsLoading = ref(true)
const reviewList = ref<DocumentVo[]>([])
const categories = ref<CategoryVo[]>([])

/** 问候语（按当前时段）。 */
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 6) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 14) return '中午好'
  if (hour < 18) return '下午好'
  return '晚上好'
})

const hasDocCenter = computed(() => user.hasPerm(PERM.docCenter))
const hasReview = computed(() => user.hasPerm(PERM.docReview))

/** 常用分类：分类树打平后按名称排序取前 6（后端暂无"热门分类"接口，先把树展示出来）。 */
const flatCategories = computed(() => {
  const out: CategoryVo[] = []
  const walk = (list: CategoryVo[]): void => {
    for (const c of list) {
      out.push(c)
      if (c.children?.length) walk(c.children)
    }
  }
  walk(categories.value)
  return out.slice(0, 6)
})

onMounted(async () => {
  if (hasDocCenter.value) {
    try {
      stats.value = await statApi.statOverview()
    } catch (e) {
      statError.value = e instanceof ApiError ? e.message : '统计加载失败'
    }
  }

  try {
    const page = await docApi.myDocuments({ pageNum: 1, pageSize: 5, status: 'DRAFT' })
    drafts.value = page.list
  } catch {
    drafts.value = []
  } finally {
    draftsLoading.value = false
  }

  if (hasReview.value) {
    try {
      const page = await reviewApi.reviewDocuments({ pageNum: 1, pageSize: 3, status: 'PUBLISHED' })
      reviewList.value = page.list
    } catch {
      reviewList.value = []
    }
  }

  try {
    categories.value = await taxApi.categoryTree()
  } catch {
    categories.value = []
  }
})
</script>

<template>
  <div>
    <!-- 横幅：固定 A 版式（左文右图） -->
    <section class="hero">
      <div class="lead">
        <h1>{{ greeting }}，{{ user.info?.realName ?? '老师' }}</h1>
        <div class="sub">
          {{ user.info?.deptName ?? '—' }} · {{ user.roles.join(' / ') || '—' }} ｜ 今天有
          {{ reviewList.length }} 条待审文档
        </div>
        <div class="acts">
          <RouterLink v-if="user.hasPerm(PERM.docCreate)" class="btn solid" to="/docs/edit">新建文档</RouterLink>
          <RouterLink v-if="user.hasPerm(PERM.docSearch)" class="btn" to="/docs">检索全校文档</RouterLink>
          <RouterLink class="btn" to="/me">我的资料</RouterLink>
        </div>
      </div>
      <div class="shot"></div>
    </section>

    <!-- 统计卡 -->
    <div class="grid g4 mb16">
      <div class="stat">
        <div class="k">全校已发布文档</div>
        <div class="v">{{ stats ? stats.publishedCount : '—' }}</div>
        <div class="m xs t3">按 doc:center 统计</div>
      </div>
      <div class="stat">
        <div class="k">我的文档</div>
        <div class="v">{{ stats ? stats.myDocumentCount : '—' }}</div>
        <div class="m xs t3">不含回收站</div>
      </div>
      <div class="stat">
        <div class="k">待我审核</div>
        <div class="v">{{ hasReview ? reviewList.length : '—' }}</div>
        <div class="m xs t3">需要 doc:review</div>
      </div>
      <div class="stat">
        <div class="k">我的收藏</div>
        <div class="v">{{ stats ? stats.myFavoriteCount : '—' }}</div>
        <div class="m xs t3">需要 doc:favorite</div>
      </div>
    </div>

    <div v-if="statError" class="alert a-warn mb16"><span class="ico">!</span><span>{{ statError }}</span></div>

    <div class="grid g-side">
      <div>
        <!-- 我的草稿 -->
        <div class="card">
          <div class="card-hd">
            <h3>我的草稿</h3>
            <span class="sub">最近 5 篇</span>
            <div class="acts"><RouterLink class="link" to="/my">全部我的文档</RouterLink></div>
          </div>
          <div class="card-bd">
            <StateBlock v-if="draftsLoading" kind="loading" :rows="4" />
            <StateBlock v-else-if="!drafts.length" kind="empty" title="你还没有创建过文档" desc="写好一篇草稿，提交后由文档管理员审核发布。">
              <RouterLink v-if="user.hasPerm(PERM.docCreate)" class="btn btn-sm" to="/docs/edit">新建文档</RouterLink>
            </StateBlock>
            <div v-else class="list">
              <RouterLink v-for="d in drafts" :key="d.id" class="list-item" :to="`/docs/${d.id}`">
                <div class="min0">
                  <div class="title">{{ d.title }}</div>
                  <div class="sub">v{{ d.versionNum }} · 更新 {{ relativeTime(d.updatedAt) }}</div>
                </div>
                <StatusBadge :status="d.status" />
              </RouterLink>
            </div>
          </div>
        </div>

        <!-- 待我审核 -->
        <div v-if="hasReview" class="card">
          <div class="card-hd">
            <h3>待我审核</h3>
            <span class="sub">按提交时间排序</span>
            <div class="acts"><RouterLink class="link" to="/review">进入审核台</RouterLink></div>
          </div>
          <div class="card-bd">
            <StateBlock v-if="!reviewList.length" kind="empty" title="当前没有待审文档" desc="提交发布后会出现在这里。" />
            <div v-else class="list">
              <RouterLink v-for="d in reviewList" :key="d.id" class="list-item" :to="`/docs/${d.id}`">
                <div class="min0">
                  <div class="title">{{ d.title }}</div>
                  <div class="sub">{{ d.authorName }} · 提交 {{ relativeTime(d.updatedAt) }}</div>
                </div>
                <span class="btn btn-sm">去审核</span>
              </RouterLink>
            </div>
          </div>
        </div>
      </div>

      <div>
        <!-- 常用分类 -->
        <div class="card">
          <div class="card-hd"><h3>常用分类</h3></div>
          <div class="card-bd">
            <StateBlock v-if="!flatCategories.length" kind="empty" title="暂无分类" desc="请先在「分类与标签」中维护分类树。" />
            <div v-else class="list">
              <RouterLink
                v-for="c in flatCategories"
                :key="c.id"
                class="cat-row"
                :to="{ name: 'docs', query: { categoryId: c.id } }"
              >
                <span>{{ c.name }}</span>
                <span class="spacer"></span>
                <span class="xs t3">查看 →</span>
              </RouterLink>
            </div>
          </div>
        </div>

        <!-- 通知 -->
        <div class="card">
          <div class="card-hd"><h3>通知</h3></div>
          <div class="card-bd">
            <div class="note-item unread">
              <div class="bd"><div class="t">系统将于 9 月 28 日 22:00–24:00 维护</div><div class="m">今天 09:10</div></div>
            </div>
            <div class="note-item unread">
              <div class="bd"><div class="t">新版《公文格式规范》已发布</div><div class="m">昨天 16:20</div></div>
            </div>
            <div class="note-item unread">
              <div class="bd"><div class="t">本学期文档审核时限调整为 3 个工作日</div><div class="m">09-19</div></div>
            </div>
            <div class="xs t3 mt12">通知接口尚未在后端提供，此为静态示意（与预览稿一致）。</div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
