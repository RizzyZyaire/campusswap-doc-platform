<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { RouterLink } from 'vue-router'
import StateBlock from '@/components/StateBlock.vue'
import Pager from '@/components/Pager.vue'
import { ApiError } from '@/api/request'
import * as logApi from '@/api/logs'
import { useUserStore } from '@/stores/user'
import { PERM } from '@/utils/perm'
import { formatDateTime } from '@/utils/format'
import type { OperationLogVo } from '@/types/log'
import type { PageVo } from '@/types'

/**
 * 操作日志（M14，权限 `sys:center`）。
 *
 * 数据来源是 AOP 切面自动写入的 `sys_operation_log`：**所有写接口**（POST/PUT/DELETE）都会被记录，
 * 重点接口用 `@OperationLog(module, action)` 补充中文模块与动作名，其余按路径与 HTTP 方法推断。
 *
 * 两处按后端契约做的事：
 *  ① 失败的操作同样在列表里（`成功` 列显示"失败"并给出原因）——审计要看的正是"谁尝试做了什么、成没成"；
 *  ② 入参摘要是**脱敏后**的文本（密码类字段已被切面打成 `***`），页面只是原样展示，不做二次加工。
 */
const user = useUserStore()

const filters = reactive<{ username: string; module: string; action: string; success: string; startTime: string; endTime: string }>({
  username: '',
  module: '',
  action: '',
  success: '',
  startTime: '',
  endTime: ''
})
const list = ref<OperationLogVo[]>([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(true)
const errorText = ref('')
const modules = ref<string[]>([])
const actions = ref<string[]>([])
/** 展开查看入参摘要的行 ID（默认收起，避免表格被长文本撑爆）。 */
const expanded = ref<string>('')

const noperm = computed(() => !user.hasPerm(PERM.sysCenter))

/** 概览：本页成功/失败条数（只统计当前页，避免为两个数字多打两次接口）。 */
const pageStat = computed(() => {
  const ok = list.value.filter((i) => i.success).length
  return { ok, fail: list.value.length - ok }
})

/**
 * 加载日志列表。
 */
async function load(): Promise<void> {
  if (noperm.value) {
    loading.value = false
    errorText.value = ''
    return
  }
  loading.value = true
  errorText.value = ''
  try {
    const res: PageVo<OperationLogVo> = await logApi.pageOperationLogs({
      username: filters.username || undefined,
      module: filters.module || undefined,
      action: filters.action || undefined,
      success: filters.success === '' ? undefined : Number(filters.success),
      startTime: filters.startTime || undefined,
      endTime: filters.endTime || undefined,
      pageNum: pageNum.value,
      pageSize: pageSize.value
    })
    list.value = res.list
    total.value = res.total
  } catch (e) {
    errorText.value = e instanceof ApiError ? e.message : '操作日志加载失败'
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/**
 * 加载筛选下拉（模块 / 动作）。
 */
async function loadFilters(): Promise<void> {
  if (noperm.value) return
  try {
    const res = await logApi.operationLogFilters()
    modules.value = res.modules
    actions.value = res.actions
  } catch {
    // 下拉失败不阻塞主流程：筛选框仍可手工输入（这里是下拉，失败就保持空列表）
    modules.value = []
    actions.value = []
  }
}

/** 筛选条件变化：回到第一页再查。 */
function onFilter(): void {
  pageNum.value = 1
  void load()
}

/** 翻页。 */
function onPage(page: number): void {
  pageNum.value = page
  void load()
}

/** 清空筛选。 */
function clearFilters(): void {
  filters.username = ''
  filters.module = ''
  filters.action = ''
  filters.success = ''
  filters.startTime = ''
  filters.endTime = ''
  onFilter()
}

/** 展开/收起某行的入参摘要。 */
function toggle(id: string): void {
  expanded.value = expanded.value === id ? '' : id
}

onMounted(async () => {
  await Promise.all([load(), loadFilters()])
})
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h1>操作日志</h1>
        <div class="desc">
          由 AOP 切面自动记录的重要写操作（新增 / 修改 / 删除 / 发布 / 审核 / 重建索引…）。
          失败的操作也会留痕，并带上失败原因；入参里的密码类字段已脱敏。
        </div>
      </div>
      <div class="acts">
        <button class="btn" :disabled="loading" @click="load">刷新</button>
      </div>
    </div>

    <StateBlock v-if="noperm" kind="noperm" :desc="errorText">
      <RouterLink class="btn btn-sm" to="/workbench">回到工作台</RouterLink>
    </StateBlock>

    <template v-else>
      <div class="card mb16">
        <div class="card-hd">
          <h3>筛选</h3>
          <span class="sub">当前页成功 {{ pageStat.ok }} 条 · 失败 {{ pageStat.fail }} 条（共 {{ total }} 条）</span>
        </div>
        <div class="card-bd">
          <div class="filter">
            <div class="field">
              <label class="label">操作人账号</label>
              <input v-model="filters.username" class="input" placeholder="如 admin" @keyup.enter="onFilter" />
            </div>
            <div class="field">
              <label class="label">模块</label>
              <select v-model="filters.module" class="select" @change="onFilter">
                <option value="">全部</option>
                <option v-for="m in modules" :key="m" :value="m">{{ m }}</option>
              </select>
            </div>
            <div class="field">
              <label class="label">动作</label>
              <select v-model="filters.action" class="select" @change="onFilter">
                <option value="">全部</option>
                <option v-for="a in actions" :key="a" :value="a">{{ a }}</option>
              </select>
            </div>
            <div class="field">
              <label class="label">结果</label>
              <select v-model="filters.success" class="select" @change="onFilter">
                <option value="">全部</option>
                <option value="1">成功</option>
                <option value="0">失败</option>
              </select>
            </div>
            <div class="field">
              <label class="label">起始时间</label>
              <input v-model="filters.startTime" class="input" placeholder="2026-10-01 00:00:00" />
            </div>
            <div class="field">
              <label class="label">结束时间</label>
              <input v-model="filters.endTime" class="input" placeholder="2026-10-31 23:59:59" />
            </div>
            <div class="field">
              <label class="label">&nbsp;</label>
              <div class="row">
                <button class="btn btn-sm" @click="onFilter">查询</button>
                <button class="btn btn-sm" @click="clearFilters">清空</button>
              </div>
            </div>
          </div>
        </div>
      </div>

      <StateBlock v-if="loading" kind="loading" :rows="6" />
      <StateBlock v-else-if="errorText" kind="error" :desc="errorText">
        <button class="btn btn-sm" @click="load">重新加载</button>
      </StateBlock>
      <StateBlock v-else-if="!list.length" kind="empty" title="还没有操作日志" desc="做一次写操作（新建文档、发布、重建索引…）再回来看。">
        <button class="btn btn-sm" @click="clearFilters">清空筛选</button>
      </StateBlock>

      <div v-else class="card">
        <div class="card-hd">
          <h3>操作记录</h3>
          <span class="sub">按时间倒序，共 {{ total }} 条</span>
        </div>
        <table class="tbl">
          <thead>
            <tr>
              <th class="w-fix2">时间</th>
              <th class="w-fix2">操作人</th>
              <th class="w-fix2">模块 · 动作</th>
              <th>摘要 / 请求</th>
              <th class="w-fix">结果</th>
              <th class="w-fix2">耗时 / IP</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="row in list" :key="row.id">
              <tr>
                <td class="sm t2">{{ formatDateTime(row.createdAt) }}</td>
                <td class="sm t2">{{ row.username ?? '—' }}</td>
                <td>
                  <span class="strong">{{ row.module }} · {{ row.action }}</span>
                </td>
                <td>
                  <div class="sm">{{ row.summary ?? '—' }}</div>
                  <div class="xs t3">{{ row.method }} {{ row.uri }}</div>
                  <button v-if="row.params" class="link xs" @click="toggle(row.id)">
                    {{ expanded === row.id ? '收起入参' : '查看入参' }}
                  </button>
                  <pre v-if="row.params && expanded === row.id" class="code">{{ row.params }}</pre>
                </td>
                <td>
                  <span class="badge" :class="row.success ? 'primary' : 'danger'">{{ row.success ? '成功' : '失败' }}</span>
                  <div v-if="row.errorMsg" class="xs danger">{{ row.errorMsg }}</div>
                </td>
                <td class="sm t2">{{ row.costMs ?? '—' }} ms<div class="xs t3">{{ row.ip ?? '—' }}</div></td>
              </tr>
            </template>
          </tbody>
        </table>
        <Pager :page-num="pageNum" :page-size="pageSize" :total="total" @change="onPage" />
      </div>
    </template>
  </div>
</template>
