<script setup lang="ts">
import { ref } from 'vue'
import { useUiStore } from '@/stores/ui'

/**
 * 「导入 Markdown 文件」按钮（上传文档的入口）。
 *
 * <p>平台只存 Markdown 纯文本 + 图片（PRD §8 非目标 O7 明确不做 Office 在线预览与格式转换），
 * 所以这里只收 `.md` / `.markdown` / `.txt`，**不静默失败**：类型不对或文件过大都会说明原因。
 * 读取在浏览器里完成（`File.text()`），再交给调用方决定是"填进编辑器"还是"直接建草稿"。</p>
 *
 * @param label    按钮文案
 * @param readonly 只读模式（归档/回收站文档）下直接禁用
 */
const props = withDefaults(defineProps<{ label?: string; readonly?: boolean }>(), {
  label: '导入 Markdown',
  readonly: false
})
const emit = defineEmits<{ (e: 'loaded', payload: { name: string; text: string }): void }>()

const ui = useUiStore()
const inputRef = ref<HTMLInputElement | null>(null)

/** 允许的扩展名。 */
const EXT = ['.md', '.markdown', '.txt']
/** 体积上限：正文列上限 10 万字，这里留足余量。 */
const MAX_BYTES = 512 * 1024

function pick(): void {
  if (props.readonly) return
  inputRef.value?.click()
}

async function onChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  const lower = file.name.toLowerCase()
  if (!EXT.some((ext) => lower.endsWith(ext))) {
    ui.err(`只支持 ${EXT.join(' / ')}：平台只存 Markdown 纯文本，Word/PDF 请先另存为 Markdown（PRD 非目标 O7）`)
    return
  }
  if (file.size > MAX_BYTES) {
    ui.err(`文件 ${(file.size / 1024).toFixed(0)} KB 超过 ${MAX_BYTES / 1024} KB 上限，请先精简后再导入`)
    return
  }
  try {
    const text = (await file.text()).replace(/\r\n/g, '\n')
    if (!text.trim()) {
      ui.err('这个文件是空的，没有可导入的内容')
      return
    }
    emit('loaded', { name: file.name, text })
  } catch {
    ui.err('读取文件失败，请重试或改用复制粘贴')
  }
}
</script>

<template>
  <button class="btn" type="button" :disabled="readonly" title="导入本地 Markdown 文件（.md / .markdown / .txt）" @click="pick">
    {{ label }}
  </button>
  <input ref="inputRef" class="hidden-file" type="file" accept=".md,.markdown,.txt,text/markdown,text/plain" @change="onChange" />
</template>
