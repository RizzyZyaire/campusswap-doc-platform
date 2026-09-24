<script setup lang="ts">
import { ref } from 'vue'
import { useUiStore } from '@/stores/ui'

/**
 * 「导入 Markdown 文件」按钮（上传文档的入口）。
 *
 * <p>平台只存 Markdown 纯文本 + 图片（PRD §8 非目标 O7 明确不做 Office 在线预览与格式转换），
 * 所以这里只收 `.md` / `.markdown` / `.txt`，**不静默失败**：哪几类文件被跳过、为什么，一次说清。
 * 读取在浏览器里完成（`File.text()`），再交给调用方决定是"填进编辑器"还是"建草稿/批量导入"。</p>
 *
 * <p>`multiple` 时按顺序读完全部合法文件，一次 `loaded` 抛出**数组**（单篇路径也走数组，调用方取第一项）。</p>
 *
 * @param label    按钮文案
 * @param readonly 只读模式（归档/回收站文档）下直接禁用
 * @param multiple 是否允许一次选多个文件
 */
const props = withDefaults(defineProps<{ label?: string; readonly?: boolean; multiple?: boolean }>(), {
  label: '导入 Markdown',
  readonly: false,
  multiple: false
})
const emit = defineEmits<{ (e: 'loaded', payload: { name: string; text: string }[]): void }>()

const ui = useUiStore()
const inputRef = ref<HTMLInputElement | null>(null)

/** 允许的扩展名。 */
const EXT = ['.md', '.markdown', '.txt']
/** 单文件体积上限：正文列上限 10 万字，这里留足余量。 */
const MAX_BYTES = 512 * 1024
/** 一次最多几篇（防手滑选几百个文件把浏览器卡住）。 */
const MAX_FILES = 50

function pick(): void {
  if (props.readonly) return
  inputRef.value?.click()
}

async function onChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  input.value = ''
  if (!files.length) return

  const badType: string[] = []
  const tooBig: string[] = []
  const unreadable: string[] = []
  const fresh: File[] = []
  for (const file of files) {
    const lower = file.name.toLowerCase()
    if (!EXT.some((ext) => lower.endsWith(ext))) {
      badType.push(file.name)
      continue
    }
    if (file.size > MAX_BYTES) {
      tooBig.push(file.name)
      continue
    }
    fresh.push(file)
  }
  let overflow = 0
  if (fresh.length > MAX_FILES) {
    overflow = fresh.length - MAX_FILES
    fresh.length = MAX_FILES
  }

  const loaded: { name: string; text: string }[] = []
  for (const file of fresh) {
    try {
      const text = (await file.text()).replace(/\r\n/g, '\n')
      if (!text.trim()) {
        unreadable.push(file.name)
        continue
      }
      loaded.push({ name: file.name, text })
    } catch {
      unreadable.push(file.name)
    }
  }

  const skipped: string[] = []
  if (badType.length) skipped.push(`${badType.length} 个不是 Markdown（Word/PDF 请先另存为 Markdown，PRD 非目标 O7）`)
  if (tooBig.length) skipped.push(`${tooBig.length} 个超过 ${MAX_BYTES / 1024} KB`)
  if (unreadable.length) skipped.push(`${unreadable.length} 个为空或读不到内容`)
  if (overflow) skipped.push(`${overflow} 个超出单次 ${MAX_FILES} 个的上限`)
  if (skipped.length) ui.warn(`已跳过：${skipped.join('；')}`)

  if (!loaded.length) {
    if (!skipped.length) ui.err('没有可导入的文件')
    return
  }
  emit('loaded', loaded)
}
</script>

<template>
  <button class="btn" type="button" :disabled="readonly" title="导入本地 Markdown 文件（.md / .markdown / .txt）" @click="pick">
    {{ label }}
  </button>
  <input
    ref="inputRef"
    class="hidden-file"
    type="file"
    :multiple="multiple"
    accept=".md,.markdown,.txt,text/markdown,text/plain"
    @change="onChange"
  />
</template>
