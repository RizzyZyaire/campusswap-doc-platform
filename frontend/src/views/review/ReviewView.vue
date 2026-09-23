<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import StatusBadge from '@/components/StatusBadge.vue'
import Pager from '@/components/Pager.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import * as reviewApi from '@/api/review'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { diffLines, diffSummary } from '@/utils/diff'
import { CHANGE_TYPE_TEXT, formatDateTime, relativeTime } from '@/utils/format'
import { renderMarkdown } from '@/utils/markdown'
import type { DocumentDetailVo, DocumentVersionVo, DocumentVo } from '@/types'

/**
 * 待我审核（UI_UX_SPECIFICATION §8.7）。
 *
 * 三张统计（待审核 / 本周已通过 / 本周已驳回）+ 左待审列表 + 右抽屉（详情 · 本次变更 · 版本并排对比 · 通过/驳回）。
 *
 * 两个后端契约决定的前端行为，别做反：
 *  ① **审核意见是必填**（`DocumentAuditDtoReq.remark` 上是 `@NotBlank`），所以「通过」也要填意见；
 *  ② 审核通过**不改状态**，只写一条 AUDIT 版本留痕；驳回是 `PUBLISHED → DRAFT` 并把理由写进 `rejectReason`。
 */
const ui = useUiStore()
const user = useUserStore()

const list = ref<DocumentVo[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(true)
const errorText = ref('')
const noperm = ref(false)

/** 抽屉：当前选中文档的详情 + 版本（最新在前）。 */
const current = ref<DocumentDetailVo | null>(null)
const versions = ref<DocumentVersionVo[]>([])
const drawerLoading = ref(false)
const drawerError = ref('')

/** 本周口径：最近 7 天（含今天）。 */
const week = reactive({ audit: 0, reject: 0, scanned: 0, failed: false })

const canAudit = computed(() => user.hasPerm(PERM.docAudit))
const canReject = computed(() => user.hasPerm(PERM.docReject))
const noActionPerm = computed(() => !canAudit.value && !canReject.value)

const bodyHtml = computed(() => renderMarkdown(current.value?.contentMd))

/** 与上一版的逐行差异（只有一个版本时为 null）。 */
const diff = computed(() => {
  if (versions.value.length < 2) return null
  const latest = versions.value[0]
  const prev = versions.value[1]
  return {
    latest,
    prev,
    lines: diffLines(prev.contentMd, latest.contentMd),
    summary: diffSummary(prev.contentMd, latest.contentMd)
  }
})

async function load(): Promise<void> {
  loading.value = true
  errorText.value = ''
  noperm.value = false
  try {
    const page = await reviewApi.reviewDocuments({
      status: 'PUBLISHED',
      keyword: keyword.value.trim() || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = page.list
    total.value = page.total
    void scanWeek(page.list)
    if (page.list.length) await open(page.list[0].id)
    else {
      current.value = null
      versions.value = []
    }
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
 * 「本周已通过 / 已驳回」的统计。
 *
 * <p>后端没有聚合接口，这里对本页待审文档逐个拉版本留痕再按时间过滤 —— 数据是真的，
 * 口径也写在卡片副文案里（只统计本页文档），不假装它是全平台周报。</p>
 */
async function scanWeek(rows: DocumentVo[]): Promise<void> {
  week.audit = 0
  week.reject = 0
  week.scanned = 0
  week.failed = false
  const since = Date.now() - 7 * 24 * 3600 * 1000
  const pages = await Promise.all(
    rows.map((r) => docApi.documentVersions(r.id, { pageNum: 1, pageSize: 50 }).catch(() => null))
  )
  for (const p of pages) {
    if (!p) continue
    week.scanned++
    for (const v of p.list) {
      const at = new Date(v.createdAt).getTime()
      if (Number.isNaN(at) || at < since) continue
      if (v.changeType === 'AUDIT') week.audit++
      else if (v.changeType === 'REJECT') week.reject++
    }
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

/* ---------------- 通过 / 驳回 ---------------- */

const auditOpen = ref(false)
const auditRemark = ref('')
const auditError = ref('')

const rejectOpen = ref(false)
const rejectReason = ref('')
const rejectError = ref('')

async function submitAudit(): Promise<void> {
  auditError.value = ''
  const remark = auditRemark.value.trim()
  // remark 在后端是 @NotBlank：空意见会被 400 顶回来，这里先拦一道，省一次往返
  if (!remark || remark.length > 255) {
    auditError.value = '审核意见不能为空且不超过255字'
    return
  }
  const doc = current.value
  if (!doc) return
  try {
    await docApi.auditDocument(doc.id, { remark })
    auditOpen.value = false
    auditRemark.value = ''
    ui.ok('已通过审核（写入了审核留痕）')
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.isConflict) {
      auditOpen.value = false
      onConflict(e.message)
      return
    }
    auditError.value = e instanceof ApiError ? e.message : '审核失败'
  }
}

async function submitReject(): Promise<void> {
  rejectError.value = ''
  const reason = rejectReason.value.trim()
  if (!reason || reason.length > 255) {
    rejectError.value = '驳回理由不能为空（最多 255 字）'
    return
  }
  const doc = current.value
  if (!doc) return
  try {
    await docApi.rejectDocument(doc.id, { reason })
    rejectOpen.value = false
    rejectReason.value = ''
    ui.ok('已驳回，理由已回传给拟稿人')
    await load()
  } catch (e) {
    if (e instanceof ApiError && e.isConflict) {
      rejectOpen.value = false
      onConflict(e.message)
      return
    }
    rejectError.value = e instanceof ApiError ? e.message : '驳回失败'
  }
}

/** 状态冲突（409「该文档状态已变更」）：关抽屉 + 重拉列表（§8.7 四态）。 */
function onConflict(message: string): void {
  ui.warn(message)
  current.value = null
  versions.value = []
  void load()
}

function onSearch(): void {
  pageNum.value = 1
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
        <h1>待我审核</h1>
        <div class="desc">
          「通过」会写一条审核留痕（状态不变，仍是已发布）；「驳回」会把文档退回草稿，
          理由原样回传到拟稿人的详情页。
        </div>
      </div>
      <div class="acts">
        <button class="btn" :disabled="loading" @click="load">刷新列表</button>
      </div>
    </div>

    <StateBlock v-if="noperm" kind="noperm" :desc="errorText">
      <RouterLink class="btn btn-sm" to="/workbench">回到工作台</RouterLink>
    </StateBlock>

    <template v-else>
      <div class="grid g3 mb16">
        <div class="stat">
          <div class="k">待审核</div>
          <div class="v">{{ total }}<small>篇</small></div>
          <div class="d">按最近更新排序，先到先审</div>
        </div>
        <div class="stat s4">
          <div class="k">近 7 天已通过</div>
          <div class="v">{{ week.failed ? '—' : week.audit }}<small>条留痕</small></div>
          <div class="d">口径：本页 {{ week.scanned }} 篇文档的 AUDIT 留痕</div>
        </div>
        <div class="stat s2">
          <div class="k">近 7 天已驳回</div>
          <div class="v">{{ week.failed ? '—' : week.reject }}<small>条留痕</small></div>
          <div class="d">驳回理由写入文档，可在详情页看到</div>
        </div>
      </div>

      <StateBlock v-if="loading" kind="loading" :rows="6" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!list.length" kind="empty" title="当前没有待审核的文档" desc="提交发布的文档会出现在这里。">
        <button class="btn btn-sm" @click="load">刷新列表</button>
      </StateBlock>

      <div v-else class="grid g-list">
        <div class="card">
          <div class="card-hd">
            <h3>待审列表</h3>
            <span class="sub">共 {{ total }} 篇</span>
          </div>
          <div class="toolbar">
            <input v-model="keyword" class="input grow" placeholder="按标题 / 摘要筛选，回车查询" @keyup.enter="onSearch" />
            <button class="btn btn-sm" @click="onSearch">查询</button>
          </div>
          <div class="list">
            <div
              v-for="d in list"
              :key="d.id"
              class="list-item"
              :class="{ on: current?.id === d.id }"
              @click="open(d.id)"
            >
              <div class="body">
                <div class="sm strong">{{ d.title }}</div>
                <div class="xs t3 mt4">{{ d.authorName ?? '—' }} · {{ d.categoryName ?? '未分类' }} · v{{ d.versionNum }}</div>
                <div class="xs t3 mt4">更新于 {{ relativeTime(d.updatedAt) }}</div>
              </div>
              <StatusBadge :status="d.status" />
            </div>
          </div>
          <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="onPage" />
        </div>

        <div class="drawer">
          <div class="drawer-hd">
            <h3>{{ current?.title ?? '选择左侧文档查看' }}</h3>
            <span class="spacer"></span>
            <StatusBadge v-if="current" :status="current.status" />
          </div>

          <StateBlock v-if="drawerLoading" kind="loading" :rows="6" />
          <StateBlock v-else-if="drawerError" kind="error" :desc="drawerError" />
          <StateBlock v-else-if="!current" kind="empty" title="还没有选中文档" desc="点左侧任意一条，这里显示详情与版本对比。" />

          <template v-else>
            <div class="drawer-bd">
              <div class="kv mb12">
                <div class="k">拟稿人</div><div>{{ current.authorName ?? '—' }}</div>
                <div class="k">分类</div><div>{{ current.categoryName ?? '未分类' }}</div>
                <div class="k">当前版本</div><div>v{{ current.versionNum }}</div>
                <div class="k">提交时间</div><div>{{ formatDateTime(current.updatedAt) }}</div>
              </div>

              <div v-if="current.rejectReason" class="alert a-danger mb12">
                <span class="ico">!</span><span>上次驳回理由：{{ current.rejectReason }}</span>
              </div>

              <div class="sec-title">本次变更</div>
              <div v-if="diff" class="alert a-info mb12">
                <span class="ico">i</span>
                <span>
                  与 v{{ diff.prev.versionNum }}（{{ CHANGE_TYPE_TEXT[diff.prev.changeType] ?? diff.prev.changeType }} ·
                  {{ diff.prev.operatorName ?? '—' }}）相比：新增 {{ diff.summary.add }} 行、删除
                  {{ diff.summary.del }} 行、正文 {{ diff.summary.charDelta >= 0 ? '+' : '' }}{{ diff.summary.charDelta }} 字。<template
                    v-if="diff.latest.changeRemark"
                  >说明：{{ diff.latest.changeRemark }}</template>
                </span>
              </div>
              <div v-else class="alert a-info mb12">
                <span class="ico">i</span><span>只有一个版本，暂无可对比的差异。</span>
              </div>

              <div class="sec-title">版本并排对比</div>
              <div v-if="diff" class="diff-grid mb12">
                <div class="diff-col">
                  <div class="diff-hd">v{{ diff.prev.versionNum }}（上一版）</div>
                  <div class="diff-body">
                    <div v-for="(l, i) in diff.lines" :key="`o${i}`" class="diff-row" :class="{ del: l.kind === 'del' }">
                      <span class="no">{{ l.oldNo ?? '' }}</span><span class="tx">{{ l.kind === 'add' ? '' : l.text }}</span>
                    </div>
                  </div>
                </div>
                <div class="diff-col">
                  <div class="diff-hd">v{{ diff.latest.versionNum }}（本次提交）</div>
                  <div class="diff-body">
                    <div v-for="(l, i) in diff.lines" :key="`n${i}`" class="diff-row" :class="{ add: l.kind === 'add' }">
                      <span class="no">{{ l.newNo ?? '' }}</span><span class="tx">{{ l.kind === 'del' ? '' : l.text }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <div class="sec-title">正文（当前版）</div>
              <div v-if="bodyHtml" class="md sm" v-html="bodyHtml"></div>
              <div v-else class="xs t3">（正文为空）</div>
            </div>

            <div class="drawer-ft">
              <span v-if="noActionPerm" class="xs t3">你没有审核操作权限，请联系系统管理员</span>
              <template v-else>
                <span class="spacer"></span>
                <button v-if="canReject" class="btn" @click="rejectOpen = true">驳回</button>
                <button v-if="canAudit" class="btn btn-primary" @click="auditOpen = true">通过</button>
              </template>
            </div>
          </template>
        </div>
      </div>
    </template>

    <!-- 通过：审核意见必填（后端 @NotBlank） -->
    <div v-if="auditOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">通过审核</div>
        <div class="modal-bd">
          <div class="field">
            <label class="label">审核意见 <span class="req">*</span></label>
            <textarea
              v-model="auditRemark"
              class="textarea"
              rows="3"
              placeholder="例如：内容与格式符合要求，同意发布（1–255 字）"
            ></textarea>
            <div class="hint">后端要求必填，会随版本留痕一起保存</div>
            <div v-if="auditError" class="hint err-text">{{ auditError }}</div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="auditOpen = false">取消</button>
          <button class="btn btn-primary" @click="submitAudit">确认通过</button>
        </div>
      </div>
    </div>

    <!-- 驳回：理由必填 1–255（BR-12） -->
    <div v-if="rejectOpen" class="modal-mask">
      <div class="modal">
        <div class="modal-hd">驳回文档</div>
        <div class="modal-bd">
          <div class="field">
            <label class="label">驳回理由 <span class="req">*</span></label>
            <textarea
              v-model="rejectReason"
              class="textarea"
              rows="3"
              placeholder="理由会原样回传给拟稿人，请写清楚要改什么"
            ></textarea>
            <div class="hint">{{ rejectReason.trim().length }} / 255 字（必填 1–255 字，BR-12）</div>
            <div v-if="rejectError" class="hint err-text">{{ rejectError }}</div>
          </div>
        </div>
        <div class="modal-ft">
          <button class="btn" @click="rejectOpen = false">取消</button>
          <button class="btn btn-danger" :disabled="!rejectReason.trim()" @click="submitReject">确认驳回</button>
        </div>
      </div>
    </div>
  </div>
</template>
