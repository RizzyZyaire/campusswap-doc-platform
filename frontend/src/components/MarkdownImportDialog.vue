<script setup lang="ts">
import { computed, ref } from 'vue'
import { ApiError } from '@/api/request'
import { createDocument } from '@/api/documents'
import type { TagVo } from '@/types'

/**
 * 批量导入 Markdown（"上传文档"的批量版）。
 *
 * <p>流程：选多个文件 → 预览（可改标题、可取消勾选、统一选分类与标签）→ 两种落地方式：</p>
 * <ul>
 *   <li><b>直接建草稿</b>：逐个 POST，结果逐条列出（成功/失败原因），列表刷新即可继续用；</li>
 *   <li><b>建草稿并逐篇编辑</b>：同样先建草稿，然后把新文档放进"待编辑队列"，
 *       编辑器顶部会出现「第 N / M 篇」的横幅，保存后自动跳下一篇 —— 适合一次导入十几篇公文再逐篇补分类。</li>
 * </ul>
 *
 * <p>只收 `.md` / `.markdown` / `.txt`（PRD 非目标 O7：不做 Office 预览与转换），单文件 ≤ 512 KB。</p>
 */
const props = defineProps<{
  /** 已选中的文件（父组件用 ImportMarkdownButton 读好后传进来）。 */
  files: { name: string; text: string }[]
  /** 分类树（拍平后的下拉项）。 */
  categories: { id: string; label: string }[]
  /** 可选标签（最多选 5 个）。 */
  tags: TagVo[]
  /** 分类原始树（用于展示层级名）——这里只需 id 与 label，故不收。 */
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'done', payload: { created: { id: string; title: string }[]; mode: 'list' | 'edit' }): void
}>()

interface Row {
  name: string
  text: string
  /** 是否导入这一篇。 */
  checked: boolean
  /** 标题（默认取文件名，可改）。 */
  title: string
  /** 正文第一个 H1（预览用）。 */
  h1: string
  /** 字数。 */
  size: number
  /** 导入结果：空 = 未处理。 */
  error: string
  /** 导入成功后的文档 id。 */
  id: string
}

const rows = ref<Row[]>(
  props.files.map((f) => {
    const h1 = /^#\s+(.+)$/m.exec(f.text)?.[1]?.trim() ?? ''
    return {
      name: f.name,
      text: f.text,
      checked: true,
      title: (h1 || f.name.replace(/\.[^.]+$/, '')).slice(0, 128),
      h1,
      size: f.text.length,
      error: '',
      id: ''
    }
  })
)

const categoryId = ref('')
const tagIds = ref<string[]>([])
const busy = ref(false)
const progress = ref({ done: 0, total: 0 })
/** 导入结束后展示的结果模式。 */
const finished = ref(false)

const picked = computed(() => rows.value.filter((r) => r.checked && !r.id))
const createdRows = computed(() => rows.value.filter((r) => r.id))
const failedRows = computed(() => rows.value.filter((r) => r.error))

function toggleTag(id: string): void {
  const i = tagIds.value.indexOf(id)
  if (i >= 0) tagIds.value.splice(i, 1)
  else if (tagIds.value.length < 5) tagIds.value.push(id)
}

/** 摘要：取正文首个非标题段落（与单篇导入、编辑器里的口径一致）。 */
function summaryOf(text: string): string | null {
  const first = text.replace(/^#.*$/m, '').split('\n').find((l) => l.trim()) ?? ''
  return first.trim().slice(0, 120) || null
}

/**
 * 逐个建草稿。
 *
 * @param mode 结束时怎么走：`list` = 留在列表；`edit` = 进"逐篇编辑"队列
 */
async function run(mode: 'list' | 'edit'): Promise<void> {
  const list = picked.value
  if (!list.length) return
  busy.value = true
  progress.value = { done: 0, total: list.length }
  for (const row of list) {
    try {
      const created = await createDocument({
        title: row.title.trim() || row.name.replace(/\.[^.]+$/, '').slice(0, 128),
        summary: summaryOf(row.text),
        contentMd: row.text,
        categoryId: categoryId.value || null,
        tagIds: [...tagIds.value],
        priceCents: 0
      })
      row.id = created.id
      row.error = ''
    } catch (e) {
      // 不吞错误：逐条落回该行的红字，方便勾掉重试
      row.error = e instanceof ApiError ? e.message : '创建失败'
    }
    progress.value.done++
  }
  busy.value = false
  finished.value = true
  emit('done', { created: createdRows.value.map((r) => ({ id: r.id, title: r.title })), mode })
}

/** 只重试失败的那些（用户改完标题或分类后再点一次）。 */
async function retryFailed(): Promise<void> {
  for (const row of failedRows.value) {
    row.error = ''
    row.checked = true
  }
  await run('list')
}

/** 未勾选的重新勾上（改完标题再导）。 */
function checkAll(): void {
  for (const r of rows.value) if (!r.id) r.checked = true
}

function labelOfTag(t: TagVo): string {
  return t.name
}

defineExpose({})
</script>

<template>
  <div class="modal-mask" @click.self="!busy && emit('close')">
    <div class="modal import-modal">
      <div class="modal-hd">
        批量导入 Markdown
        <span class="sub">{{ rows.length }} 个文件 · 已选 {{ picked.length }} 篇</span>
      </div>

      <div class="modal-bd">
        <!-- 逐篇：标题可改、可取消勾选 -->
        <table class="tbl import-tbl">
          <thead>
            <tr>
              <th class="w-fix2">文件</th>
              <th>导入后的标题（可改）</th>
              <th class="w-fix">字数</th>
              <th class="w-fix">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.name">
              <td>
                <label class="row">
                  <input v-model="r.checked" type="checkbox" :disabled="Boolean(r.id)" />
                  <span class="sm ellip" :title="r.name">{{ r.name }}</span>
                </label>
              </td>
              <td>
                <input v-model="r.title" class="input input-compact w-full" :disabled="Boolean(r.id)" />
                <div v-if="r.h1 && r.h1 !== r.title" class="hint">正文标题：{{ r.h1 }}</div>
              </td>
              <td class="sm t2">{{ r.size }} 字</td>
              <td>
                <span v-if="r.id" class="badge published">已建草稿</span>
                <span v-else-if="r.error" class="badge trash">失败</span>
                <span v-else class="xs t3">待导入</span>
                <div v-if="r.error" class="hint err-text">{{ r.error }}</div>
              </td>
            </tr>
          </tbody>
        </table>

        <!-- 统一设置：一批公文通常同分类同标签 -->
        <div class="filter mt12">
          <div class="field">
            <label class="label">统一分类</label>
            <select v-model="categoryId" class="select" :disabled="busy">
              <option value="">未分类</option>
              <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.label }}</option>
            </select>
            <div class="hint">分类是检索的主要依据，建议先选好；导入后也能在编辑器里改</div>
          </div>
          <div class="field wide">
            <label class="label">统一标签（最多 5 个）</label>
            <div class="chip-pick">
              <span
                v-for="t in tags"
                :key="t.id"
                class="tag"
                :class="{ on: tagIds.includes(t.id) }"
                @click="!busy && toggleTag(t.id)"
              >
                {{ labelOfTag(t) }}
              </span>
            </div>
          </div>
        </div>

        <div v-if="busy" class="alert a-info mt12">
          <span class="ico">◌</span>
          <span>正在导入 {{ progress.done }} / {{ progress.total }} …</span>
        </div>

        <div v-if="finished" class="alert mt12" :class="failedRows.length ? 'a-warn' : 'a-ok'">
          <span class="ico">{{ failedRows.length ? '!' : '✓' }}</span>
          <span>
            成功 {{ createdRows.length }} 篇<template v-if="failedRows.length">，失败 {{ failedRows.length }} 篇</template>。
            <template v-if="failedRows.length">失败的行已在上面标红，改完可以点「重试失败的」。</template>
            <template v-else>草稿已在「我的文档 → 草稿」里，随时可继续编辑。</template>
          </span>
        </div>
      </div>

      <div class="modal-ft">
        <button class="btn" :disabled="busy" @click="checkAll">全选未导入</button>
        <button v-if="failedRows.length" class="btn" :disabled="busy" @click="retryFailed">重试失败的</button>
        <span class="spacer"></span>
        <button class="btn" :disabled="busy" @click="emit('close')">
          {{ finished ? '关闭' : '取消' }}
        </button>
        <button class="btn" :disabled="busy || !picked.length" @click="run('list')">
          直接建 {{ picked.length }} 篇草稿
        </button>
        <button class="btn btn-primary" :disabled="busy || !picked.length" @click="run('edit')">
          建草稿并逐篇编辑
        </button>
      </div>
    </div>
  </div>
</template>
