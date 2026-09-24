import { computed, ref } from 'vue'
import { defineStore } from 'pinia'

/**
 * 批量导入的「待编辑队列」。
 *
 * <p>场景：一次选 5 个 .md 建草稿，用户想**逐篇**补分类与标签。若把"下一篇是谁"只放在路由参数里，
 * 刷新或误点返回就丢了；所以队列放 store 并存进 `sessionStorage`（同标签页内有效，关掉即散）。</p>
 */
interface QueueItem {
  id: string
  title: string
}

const KEY = 'campusswap.importQueue'

/** 从 sessionStorage 恢复（刷新页面不丢）。 */
function restore(): QueueItem[] {
  try {
    const raw = sessionStorage.getItem(KEY)
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    return Array.isArray(parsed) ? (parsed as QueueItem[]) : []
  } catch {
    return []
  }
}

export const useImportQueueStore = defineStore('importQueue', () => {
  const items = ref<QueueItem[]>(restore())
  /** 队列总共多少篇（用于显示"第 N / M 篇"）。 */
  const total = ref<number>(items.value.length)

  const hasNext = computed(() => items.value.length > 0)
  const pending = computed(() => items.value.length)
  /** 当前正在编辑的是第几篇（1 起）。 */
  const current = computed(() => Math.max(1, total.value - items.value.length))

  function persist(): void {
    sessionStorage.setItem(KEY, JSON.stringify(items.value))
  }

  /**
   * 建立队列（批量导入后调用）。
   *
   * @param list 待编辑的文档（按导入顺序）
   */
  function start(list: QueueItem[]): void {
    items.value = [...list]
    total.value = list.length
    persist()
  }

  /** 取走下一篇（编辑器"下一篇"时调用）。 */
  function shift(): QueueItem | null {
    const first = items.value.shift() ?? null
    persist()
    return first
  }

  function clear(): void {
    items.value = []
    total.value = 0
    sessionStorage.removeItem(KEY)
  }

  return { items, total, hasNext, pending, current, start, shift, clear }
})
