<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import { ApiError } from '@/api/request'
import * as docApi from '@/api/documents'
import * as taxApi from '@/api/taxonomy'
import { uploadImage } from '@/api/files'
import { useUiStore } from '@/stores/ui'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { markdownSnippet, renderMarkdown } from '@/utils/markdown'
import type { CategoryVo, DocumentCreateDtoReq, DocumentDetailVo, DocumentUpdateDtoReq, TagVo } from '@/types'

/**
 * 新建 / 编辑（UI_UX_SPECIFICATION §8.5）。
 *
 * 三处容易做错、这里专门处理的地方：
 *  ① **保存必须回传 `versionNum`**（打开编辑页时读到的那一版）—— 陈旧表单防覆盖，见 ARCHITECTURE §17 ADR-07；
 *  ② 409 **分两种**：状态冲突（归档 / 回收站）切只读；版本冲突**不切只读**，给横幅 +「刷新内容」，
 *     由用户重新确认后再保存；
 *  ③ 图片上传失败要在源文原地留占位符，并且**有未完成的上传时禁止保存**。
 */
const route = useRoute()
const router = useRouter()
const ui = useUiStore()
const user = useUserStore()

const docId = computed(() => (route.params.id ? String(route.params.id) : ''))
const isEdit = computed(() => Boolean(docId.value))

const loading = ref(false)
const saving = ref(false)
const noperm = ref(false)
const loadError = ref('')
const pageError = ref('')
const formError = ref('')
const fieldErrors = reactive<Record<string, string>>({})
/** 只读模式（状态冲突：归档 / 回收站）。 */
const readOnly = ref(false)
const readOnlyReason = ref('')
/** 版本冲突（不切只读）：横幅文案 + 库中最新版本号。 */
const conflict = ref('')
/** 表单基线：与当前表单逐字比较得出"有没有未保存的修改"（比 watcher 打标记可靠）。 */
const baseline = ref('')

const form = reactive({
  title: '',
  summary: '',
  contentMd: '',
  categoryId: '',
  tagIds: [] as string[],
  priceCents: 0
})
const edited = computed(() => !readOnly.value && JSON.stringify(form) !== baseline.value)
/** 打开编辑页时读到的那一版（PUT 必填）。 */
const versionNum = ref(0)
const status = ref('')

const categories = ref<CategoryVo[]>([])
const tags = ref<TagVo[]>([])
const mode = ref<'edit' | 'split' | 'preview'>('split')
const previewHtml = computed(() => renderMarkdown(form.contentMd))

const titleRef = ref<HTMLTextAreaElement | null>(null)
const contentRef = ref<HTMLTextAreaElement | null>(null)
const fileRef = ref<HTMLInputElement | null>(null)

/** 上传占位符：`![上传中 42%](upload:<id>)`，成功替换为真实图片，失败留「点击重试」。 */
interface PendingUpload {
  id: string
  name: string
  percent: number
  error: string
  file: File
}
const uploads = ref<PendingUpload[]>([])
const uploading = computed(() => uploads.value.some((u) => !u.error))
const failedUploads = computed(() => uploads.value.filter((u) => u.error))

const canUpload = computed(() => user.hasPerm(PERM.docUpload))
const canPublish = computed(() => user.hasPerm(PERM.docPublish))

/** 扁平化分类树（下拉用；用全角空格缩进表示层级，`\u3000` 写成转义避免源码里出现不规则空白）。 */
const categoryOptions = computed(() => {
  const out: { id: string; label: string }[] = []
  const walk = (nodes: CategoryVo[], depth: number): void => {
    for (const n of nodes) {
      out.push({ id: n.id, label: '\u3000'.repeat(depth) + n.name })
      if (n.children?.length) walk(n.children, depth + 1)
    }
  }
  walk(categories.value, 0)
  return out
})

/** 把本页数据填进表单（首次加载 / 刷新内容共用）。 */
function fillFrom(detail: DocumentDetailVo): void {
  form.title = detail.title
  form.summary = detail.summary ?? ''
  form.contentMd = detail.contentMd ?? ''
  form.categoryId = detail.categoryId ?? ''
  form.tagIds = (detail.tags ?? []).map((t) => t.id)
  form.priceCents = detail.priceCents
  versionNum.value = detail.versionNum
  status.value = detail.status
  baseline.value = JSON.stringify(form)
  if (detail.rejectReason) ui.warn(`审核未通过：${detail.rejectReason}`)
}

/** 新建成功后回填（此时详情还没重拉）。 */
function fillCreated(detail: DocumentDetailVo): void {
  versionNum.value = detail.versionNum
  status.value = detail.status
  baseline.value = JSON.stringify(form)
}

async function load(): Promise<void> {
  loading.value = true
  loadError.value = ''
  noperm.value = false
  try {
    const [tree, tagPage] = await Promise.all([taxApi.categoryTree(), taxApi.tagList({ pageNum: 1, pageSize: 100 })])
    categories.value = tree
    tags.value = tagPage.list
    if (!isEdit.value) {
      loading.value = false
      return
    }
    const detail = await docApi.documentDetail(docId.value)
    if (!detail.canEdit) {
      // 归档 / 回收站：属主的文档也不能改 → 按 §8.5 的「状态冲突」走只读，而不是整页 403；
      // 其余情况（他人草稿、管理员视角）才是真的没权限编辑。
      if (detail.status === 'ARCHIVED' || detail.status === 'TRASH') {
        readOnly.value = true
        readOnlyReason.value =
          detail.status === 'ARCHIVED'
            ? '归档文档为只读，请先在「我的文档」里恢复上架'
            : '这篇文档在回收站中，请先恢复为草稿再编辑'
        fillFrom(detail)
      } else {
        noperm.value = true
        loadError.value = '你没有编辑这篇文档的权限'
      }
      loading.value = false
      return
    }
    fillFrom(detail)
  } catch (e) {
    if (e instanceof ApiError && (e.isForbidden || e.code === 404)) {
      noperm.value = true
      loadError.value = e.message
    } else {
      loadError.value = e instanceof ApiError ? e.message : '加载失败'
    }
  } finally {
    loading.value = false
  }
}

/** 字段级错误：后端 400 的中文文案里带字段名，落到对应输入框并聚焦。 */
function applyFieldError(message: string): boolean {
  const map: [RegExp, string][] = [
    [/标题/, 'title'],
    [/摘要/, 'summary'],
    [/正文/, 'contentMd'],
    [/价格|积分/, 'priceCents'],
    [/标签/, 'tags'],
    [/分类/, 'categoryId'],
    [/版本号/, 'versionNum']
  ]
  for (const [re, field] of map) {
    if (re.test(message)) {
      fieldErrors[field] = message
      void nextTick(() => {
        const el = field === 'title' ? titleRef.value : field === 'contentMd' ? contentRef.value : null
        el?.focus()
        el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      })
      return true
    }
  }
  return false
}

function clearErrors(): void {
  pageError.value = ''
  formError.value = ''
  conflict.value = ''
  for (const k of Object.keys(fieldErrors)) delete fieldErrors[k]
}

/** 当前表单对应的请求体（新建 / 编辑共用前 6 个字段）。 */
function baseBody(): DocumentCreateDtoReq {
  return {
    title: form.title.trim(),
    summary: form.summary.trim() || null,
    contentMd: form.contentMd || null,
    categoryId: form.categoryId || null,
    tagIds: [...form.tagIds],
    priceCents: form.priceCents || 0
  }
}

/**
 * 保存（新建 = POST，编辑 = PUT + `versionNum`）。
 *
 * @returns 保存后的文档 ID（失败返回 null）
 */
async function save(): Promise<string | null> {
  clearErrors()
  if (!form.title.trim()) {
    fieldErrors.title = '文档标题不能为空且不超过128字'
    void nextTick(() => titleRef.value?.focus())
    return null
  }
  if (uploads.value.length) {
    formError.value = '还有图片没有上传完成（失败的可点「点击重试」），全部处理完才能保存。'
    return null
  }
  if (form.tagIds.length > 5) {
    fieldErrors.tags = '标签最多 5 个'
    return null
  }
  saving.value = true
  try {
    if (!isEdit.value) {
      const created = await docApi.createDocument(baseBody())
      fillCreated(created)
      ui.ok('已保存为草稿')
      // 换成编辑态地址：刷新后仍是编辑这篇（否则会退回"新建"而看起来像丢了文档）。
      // 地址一变 route.params.id 的 watch 会触发一次 load()，这里跳过它 —— 刚存的数据就是最新的。
      skipNextLoad = true
      await router.replace({ name: 'docs-edit', params: { id: created.id } })
      return created.id
    }
    const body: DocumentUpdateDtoReq = { id: docId.value, versionNum: versionNum.value, ...baseBody() }
    const updated = await docApi.updateDocument(docId.value, body)
    versionNum.value = updated.versionNum
    status.value = updated.status
    baseline.value = JSON.stringify(form)
    ui.ok(`已保存（v${updated.versionNum}）`)
    return updated.id
  } catch (e) {
    handleSaveError(e)
    return null
  } finally {
    saving.value = false
  }
}

/** 保存失败的统一处置：400 落字段、403 只读、409 分两种。 */
function handleSaveError(e: unknown): void {
  if (!(e instanceof ApiError)) {
    pageError.value = '保存失败，请稍后重试'
    return
  }
  if (e.isBadRequest) {
    if (!applyFieldError(e.message)) pageError.value = e.message
    return
  }
  if (e.isForbidden) {
    readOnly.value = true
    readOnlyReason.value = e.message
    return
  }
  if (e.isConflict) {
    // 版本冲突：**不切只读**，顶部横幅 +「刷新内容」；其余 409 是状态冲突 → 切只读。
    if (/已被他人修改|当前版本/.test(e.message)) {
      conflict.value = e.message
      ui.warn(e.message)
    } else {
      readOnly.value = true
      readOnlyReason.value = e.message
    }
    return
  }
  pageError.value = e.message
}

/** 新建成功后的 `router.replace` 会改 route.params.id → 跳过随之而来的那一次重载。 */
let skipNextLoad = false

async function refreshContent(): Promise<void> {
  try {
    const detail = await docApi.documentDetail(docId.value)
    fillFrom(detail)
    conflict.value = ''
    ui.ok(`已刷新到 v${detail.versionNum}，请确认内容后重新保存`)
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '刷新失败')
  }
}

/** 保存并提交发布（缺 `doc:publish` 时按钮不渲染）。 */
async function saveAndPublish(): Promise<void> {
  const id = await save()
  if (!id) return
  try {
    const published = await docApi.publishDocument(id)
    status.value = published.status
    versionNum.value = published.versionNum
    ui.ok('已提交发布，等待文档管理员审核')
    await router.push({ name: 'docs-detail', params: { id } })
  } catch (e) {
    ui.err(e instanceof ApiError ? e.message : '提交发布失败')
  }
}

/* ---------------- 图片上传（工具栏 / 粘贴 / 拖拽 三入口） ---------------- */

function insertAtCursor(text: string): void {
  const el = contentRef.value
  if (!el) {
    form.contentMd += text
    return
  }
  const start = el.selectionStart ?? form.contentMd.length
  const end = el.selectionEnd ?? start
  form.contentMd = form.contentMd.slice(0, start) + text + form.contentMd.slice(end)
  void nextTick(() => {
    el.focus()
    const pos = start + text.length
    el.setSelectionRange(pos, pos)
  })
}

async function startUpload(file: File): Promise<void> {
  const id = `${Date.now()}-${Math.random().toString(36).slice(2, 7)}`
  const item: PendingUpload = { id, name: file.name, percent: 0, error: '', file }
  uploads.value.push(item)
  const placeholder = `![上传中 0%](upload:${id})`
  insertAtCursor(`\n${placeholder}\n`)
  await doUpload(item)
}

async function doUpload(item: PendingUpload): Promise<void> {
  item.error = ''
  item.percent = 0
  try {
    const vo = await uploadImage(item.file, (p) => {
      item.percent = p
      replacePlaceholder(item.id, `![上传中 ${p}%](upload:${item.id})`)
    })
    replacePlaceholder(item.id, `![${item.name}](${vo.url})`)
    uploads.value = uploads.value.filter((u) => u.id !== item.id)
    ui.ok('图片已插入')
  } catch (e) {
    item.error = e instanceof ApiError ? e.message : '上传失败'
    replacePlaceholder(item.id, `![上传失败：${item.error} - 点击重试](upload:${item.id})`)
  }
}

function replacePlaceholder(id: string, next: string): void {
  form.contentMd = form.contentMd.replace(new RegExp(`!\\[[^\\]]*\\]\\(upload:${id}\\)`), next)
}

function onPickFile(): void {
  fileRef.value?.click()
}

function onFileChange(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  void enqueue(files)
}

async function enqueue(files: File[]): Promise<void> {
  const images = files.filter((f) => f.type.startsWith('image/'))
  if (!images.length) return
  for (const f of images) await startUpload(f)
}

function onPaste(event: ClipboardEvent): void {
  if (!canUpload.value) return
  const files = Array.from(event.clipboardData?.files ?? [])
  if (!files.length) return
  event.preventDefault()
  void enqueue(files)
}

function onDrop(event: DragEvent): void {
  if (!canUpload.value) return
  const files = Array.from(event.dataTransfer?.files ?? [])
  if (!files.length) return
  event.preventDefault()
  void enqueue(files)
}

function retryUpload(item: PendingUpload): void {
  void doUpload(item)
}

/* ---------------- 工具栏 ---------------- */

function applySnippet(kind: Parameters<typeof markdownSnippet>[0]): void {
  const el = contentRef.value
  const selected = el ? form.contentMd.slice(el.selectionStart ?? 0, el.selectionEnd ?? 0) : ''
  insertAtCursor(markdownSnippet(kind, selected))
}

function toggleTag(id: string): void {
  const i = form.tagIds.indexOf(id)
  if (i >= 0) form.tagIds.splice(i, 1)
  else if (form.tagIds.length < 5) form.tagIds.push(id)
  else ui.warn('标签最多选 5 个')
}

/* ---------------- 离开确认 ---------------- */

function confirmLeave(): boolean {
  if (!edited.value || readOnly.value) return true
  return window.confirm('有未保存的修改，确定离开吗？')
}

onBeforeRouteLeave(() => confirmLeave())

function onBeforeUnload(event: BeforeUnloadEvent): void {
  if (edited.value) event.preventDefault()
}

watch(
  () => route.params.id,
  () => {
    if (skipNextLoad) {
      skipNextLoad = false
      return
    }
    void load()
  }
)

onMounted(() => {
  window.addEventListener('beforeunload', onBeforeUnload)
  void load()
})

onBeforeUnmount(() => window.removeEventListener('beforeunload', onBeforeUnload))
</script>

<template>
  <div>
    <div v-if="loading" class="sk">
      <div class="sk-line w40"></div>
      <div class="sk-line"></div>
      <div class="sk-line w80"></div>
      <div class="sk-line"></div>
    </div>

    <StateBlock v-else-if="noperm" kind="noperm" :desc="loadError">
      <RouterLink class="btn btn-sm" to="/my">回到我的文档</RouterLink>
    </StateBlock>

    <StateBlock v-else-if="loadError" kind="error" :desc="loadError">
      <button class="btn btn-sm" @click="load">重新加载</button>
    </StateBlock>

    <template v-else>
      <div class="page-head">
        <div>
          <h1>{{ isEdit ? '编辑文档' : '新建文档' }}</h1>
          <div class="desc">
            左侧写作，右侧设置分类、标签与摘要。保存草稿不对外可见；提交后由文档管理员审核。
            <template v-if="isEdit">当前版本 v{{ versionNum }}，保存时会带上它，被他人改过会提示你刷新。</template>
          </div>
        </div>
        <div class="acts">
          <RouterLink class="btn" :to="isEdit ? `/docs/${docId}` : '/my'">取消</RouterLink>
          <button v-if="!readOnly" class="btn" :disabled="saving" @click="save">保存草稿</button>
          <button v-if="!readOnly && canPublish" class="btn btn-primary" :disabled="saving" @click="saveAndPublish">
            提交审核
          </button>
        </div>
      </div>

      <div v-if="readOnly" class="alert a-warn mb16">
        <span class="ico">!</span>
        <span>{{ readOnlyReason || '该文档当前状态不允许编辑' }}（页面为只读，正文仍可复制）</span>
      </div>

      <div v-if="conflict" class="conflict-bar mb16">
        <span class="ico">!</span>
        <span>{{ conflict }}</span>
        <button class="btn btn-sm" @click="refreshContent">刷新内容</button>
      </div>

      <div v-if="pageError" class="alert a-danger mb16">
        <span class="ico">!</span><span>{{ pageError }}</span>
      </div>

      <div v-if="uploading" class="alert a-info mb16">
        <span class="ico">◌</span>
        <span>正在上传图片（{{ uploads.length }} 张）…全部完成后才能保存，正文里先留占位符。</span>
      </div>

      <div v-if="failedUploads.length" class="alert a-warn mb16">
        <span class="ico">!</span>
        <span>
          有 {{ failedUploads.length }} 张图片没有上传成功，保存已被阻止：
        </span>
        <span class="row wrap">
          <button v-for="u in failedUploads" :key="u.id" class="btn btn-sm" @click="retryUpload(u)">
            点击重试 · {{ u.name }}
          </button>
        </span>
      </div>

      <div class="editor">
        <div>
          <div class="pane mb16">
            <div class="card-bd tight">
              <textarea
                ref="titleRef"
                v-model="form.title"
                class="input input-title"
                rows="1"
                placeholder="请输入文档标题"
                :disabled="readOnly"
                :class="{ err: fieldErrors.title }"
              ></textarea>
              <div v-if="fieldErrors.title" class="hint err-text">{{ fieldErrors.title }}</div>
            </div>
          </div>

          <div class="pane">
            <div class="toolbar-md">
              <button class="tool" title="加粗" :disabled="readOnly" @click="applySnippet('bold')"><b>B</b></button>
              <button class="tool" title="一级标题" :disabled="readOnly" @click="applySnippet('h1')">H1</button>
              <button class="tool" title="二级标题" :disabled="readOnly" @click="applySnippet('h2')">H2</button>
              <span class="tool sep"></span>
              <button class="tool" title="无序列表" :disabled="readOnly" @click="applySnippet('ul')">•</button>
              <button class="tool" title="有序列表" :disabled="readOnly" @click="applySnippet('ol')">1.</button>
              <button class="tool" title="引用" :disabled="readOnly" @click="applySnippet('quote')">❝</button>
              <button class="tool" title="代码" :disabled="readOnly" @click="applySnippet('code')">&lt;/&gt;</button>
              <span class="tool sep"></span>
              <button class="tool" title="表格" :disabled="readOnly" @click="applySnippet('table')">▦</button>
              <button class="tool" title="分隔线" :disabled="readOnly" @click="applySnippet('hr')">―</button>
              <!-- 缺 doc:upload 时插图入口不渲染（§8.5 无权限态） -->
              <button v-if="canUpload && !readOnly" class="tool" title="插入图片" @click="onPickFile">🖼</button>
              <input ref="fileRef" class="hidden-file" type="file" accept="image/*" multiple @change="onFileChange" />
              <span class="tool sep"></span>
              <button class="tool" :class="{ on: mode === 'edit' }" @click="mode = 'edit'">编辑</button>
              <button class="tool" :class="{ on: mode === 'split' }" @click="mode = 'split'">双栏</button>
              <button class="tool" :class="{ on: mode === 'preview' }" @click="mode = 'preview'">预览</button>
              <span class="spacer"></span>
              <span class="xs t3">{{ form.contentMd.length }} 字</span>
            </div>

            <div class="md-wrap" :class="{ split: mode === 'split' }">
              <textarea
                v-show="mode !== 'preview'"
                ref="contentRef"
                v-model="form.contentMd"
                class="ta"
                :disabled="readOnly"
                placeholder="# 标题&#10;&#10;正文支持 Markdown：列表、表格、引用、代码块；插图可直接拖拽或粘贴。"
                @paste="onPaste"
                @drop="onDrop"
                @dragover.prevent
              ></textarea>
              <div v-if="mode !== 'edit'" class="preview md" v-html="previewHtml"></div>
            </div>
          </div>

          <div v-if="fieldErrors.contentMd" class="hint err-text mt8">{{ fieldErrors.contentMd }}</div>
        </div>

        <div>
          <div class="pane mb16">
            <div class="pane-hd">发布设置</div>
            <div class="card-bd">
              <div class="field mb12">
                <label class="label">分类 <span class="req">*</span></label>
                <select v-model="form.categoryId" class="select" :disabled="readOnly" :class="{ err: fieldErrors.categoryId }">
                  <option value="">未分类</option>
                  <option v-for="c in categoryOptions" :key="c.id" :value="c.id">{{ c.label }}</option>
                </select>
                <div class="hint">用于全校检索与归档</div>
                <div v-if="fieldErrors.categoryId" class="hint err-text">{{ fieldErrors.categoryId }}</div>
              </div>

              <div class="field mb12">
                <label class="label">拟稿人</label>
                <input class="input" :value="user.info?.realName ?? '当前登录用户'" disabled />
                <div class="hint">自动取登录态；文档表不含「发文单位」列（PRD §8 O6 单单位内部使用）</div>
              </div>

              <div class="field mb12">
                <label class="label">标签（最多 5 个）</label>
                <div class="chip-pick">
                  <span
                    v-for="t in tags"
                    :key="t.id"
                    class="tag"
                    :class="{ on: form.tagIds.includes(t.id) }"
                    @click="readOnly || toggleTag(t.id)"
                  >
                    {{ t.name }}
                  </span>
                </div>
                <div class="hint">已选 {{ form.tagIds.length }} / 5</div>
                <div v-if="fieldErrors.tags" class="hint err-text">{{ fieldErrors.tags }}</div>
              </div>

              <div class="field mb12">
                <label class="label">摘要</label>
                <textarea
                  v-model="form.summary"
                  class="textarea"
                  rows="3"
                  :disabled="readOnly"
                  :class="{ err: fieldErrors.summary }"
                  placeholder="一两句话说明这份文档解决什么问题（列表页会展示）"
                ></textarea>
                <div v-if="fieldErrors.summary" class="hint err-text">{{ fieldErrors.summary }}</div>
              </div>

              <div class="field mb12">
                <label class="label">积分标记</label>
                <input v-model.number="form.priceCents" class="input" type="number" min="0" :disabled="readOnly" />
                <div class="hint">单位：分。0 = 免费；本作业不做真实支付（PRD §8 O3）</div>
                <div v-if="fieldErrors.priceCents" class="hint err-text">{{ fieldErrors.priceCents }}</div>
              </div>

              <div class="field">
                <label class="label">可见范围</label>
                <input class="input" value="全校可见（发布后）｜草稿仅本人可见" disabled />
                <div class="hint">数据模型没有分级可见字段，这是当前唯一行为（草稿=仅本人，已发布=全校）</div>
              </div>
            </div>
          </div>

          <div v-if="mode !== 'split'" class="pane">
            <div class="pane-hd">实时预览</div>
            <div v-if="form.contentMd" class="preview md" v-html="previewHtml"></div>
            <StateBlock v-else kind="empty" title="左侧输入 Markdown，这里实时预览" desc="标题与摘要也会随输入更新。" />
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
