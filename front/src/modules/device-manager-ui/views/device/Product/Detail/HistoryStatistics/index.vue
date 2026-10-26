<template>
  <a-card :bordered="false" title="累计表码历史统计">
    <a-alert
      type="info"
      show-icon
      message="请直接启用需要统计的数值型累计采集项，例如累计电量；电压、瞬时功率等属性不适用于累计差值统计。启用后从北京时间今天零点开始生成小时、日边界表码。历史数据需手动提交回填任务。空表码不会按零计算。"
    />
    <div class="toolbar">
      <span>时间均为北京时间（Asia/Shanghai）</span>
      <a-button :loading="loading" @click="loadConfigs">刷新</a-button>
    </div>
    <a-table
      row-key="propertyId"
      size="middle"
      :loading="loading"
      :columns="columns"
      :data-source="rows"
      :pagination="{ pageSize: 20 }"
      :scroll="{ x: 1200 }"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'property'">
          <div>{{ record.name }}</div>
          <small class="secondary">{{ record.propertyId }} · {{ record.type }}</small>
          <a-tag v-if="!record.available" color="warning">属性已删除或改为非数值型</a-tag>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-switch
            :checked="record.enabled"
            :loading="busyProperty === record.propertyId"
            :disabled="!canUpdate || (!record.available && !record.enabled)"
            @change="(checked) => changeEnabled(record as HistoryStatisticsRow, checked === true)"
          />
          <span class="state-text">{{ record.configured ? (record.enabled ? '启用' : '停用') : '未配置' }}</span>
          <div v-if="record.status" class="secondary">{{ record.status }} · 版本 {{ record.version ?? '—' }}</div>
        </template>
        <template v-else-if="column.key === 'startTime'">
          {{ formatShanghaiTime(record.startTime) }}
        </template>
        <template v-else-if="column.key === 'coverage'">
          <template v-if="record.coverageFrom != null && record.coverageTo != null">
            {{ formatShanghaiTime(record.coverageFrom) }}<br />
            至 {{ formatShanghaiTime(record.coverageTo) }}
          </template>
          <span v-else>—</span>
        </template>
        <template v-else-if="column.key === 'pending'">
          <template v-if="record.pendingFrom != null && record.pendingTo != null">
            {{ formatShanghaiTime(record.pendingFrom) }}<br />
            至 {{ formatShanghaiTime(record.pendingTo) }}
          </template>
          <span v-else>—</span>
        </template>
        <template v-else-if="column.key === 'result'">
          <div>成功：{{ formatShanghaiTime(record.lastSuccess) }}</div>
          <div>失败：{{ formatShanghaiTime(record.lastFailure) }}</div>
          <a-tooltip v-if="record.lastError" :title="record.lastError">
            <span class="error-text">{{ record.lastError }}</span>
          </a-tooltip>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-space>
            <a-button
              type="link"
              size="small"
              :disabled="!canUpdate || !record.enabled || !record.available"
              @click="openRebuild(record as HistoryStatisticsRow)"
            >历史回填 / 范围重算</a-button>
            <a-button
              v-if="record.taskId"
              type="link"
              size="small"
              @click="openTask(record.taskId)"
            >查看任务</a-button>
          </a-space>
        </template>
      </template>
      <template #emptyText>暂无数值型采集项</template>
    </a-table>
  </a-card>

  <a-modal
    v-model:open="rebuildVisible"
    title="历史回填 / 范围重算"
    ok-text="提交任务"
    :confirm-loading="rebuildLoading"
    @ok="submitRebuild"
  >
    <p>采集项：{{ selectedRow?.name }}（{{ selectedRow?.propertyId }}）</p>
    <p class="secondary">任务将重算所选范围内的小时、日边界；已提交的结果保留，取消只停止后续处理。</p>
    <a-range-picker
      v-model:value="rebuildRange"
      show-time
      format="YYYY-MM-DD HH:mm:ss"
      value-format="YYYY-MM-DD HH:mm:ss"
      :placeholder="['开始时间', '结束时间']"
      style="width: 100%"
    />
    <div class="secondary range-note">请选择北京时间，结束时间必须晚于开始时间且不超过当前时间。</div>
  </a-modal>

  <a-modal v-model:open="taskVisible" title="历史统计任务" :footer="null" @cancel="stopTaskPoll">
    <a-spin :spinning="taskLoading">
      <template v-if="task">
        <a-descriptions :column="1" size="small">
          <a-descriptions-item label="任务 ID">{{ task.id }}</a-descriptions-item>
          <a-descriptions-item label="状态">
            <a-tag :color="taskColor(task.status)">{{ taskStatusText(task.status) }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="进度">
            {{ task.processed ?? 0 }} / {{ task.total ?? '—' }}
          </a-descriptions-item>
          <a-descriptions-item v-if="task.lastError" label="错误">
            <span class="error-text">{{ task.lastError }}</span>
          </a-descriptions-item>
        </a-descriptions>
        <a-progress :percent="taskPercent" :status="task.status === 'FAILED' ? 'exception' : task.status === 'SUCCESS' ? 'success' : 'active'" />
        <div class="task-actions">
          <a-button @click="refreshTask">刷新</a-button>
          <a-button
            v-if="canUpdate && isActiveTask(task.status)"
            danger
            :loading="cancelLoading"
            @click="cancelTask"
          >取消后续处理</a-button>
        </div>
      </template>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useAuthStore } from '@/store'
import { onlyMessage } from '@jetlinks-web/utils'
import { useProductStore } from '../../../../../store/product'
import {
  cancelHistoryStatisticsTask,
  queryHistoryStatistics,
  queryHistoryStatisticsTask,
  rebuildHistoryStatistics,
  updateHistoryStatistics,
} from '../../../../../api/historyStatistics'
import type { HistoryStatisticsConfig, HistoryStatisticsTask } from '../../../../../api/historyStatistics'
import {
  formatShanghaiTime,
  historyStatisticsRows,
  numericProperties,
  validateRebuildRange,
} from './utils'
import type { HistoryStatisticsRow } from './utils'

const productStore = useProductStore()
const authStore = useAuthStore()
const canUpdate = computed(() => authStore.hasPermission('device/Product:update'))
const productId = computed(() => productStore.current?.id)
const properties = computed(() => numericProperties(productStore.current?.metadata))
const configs = ref<HistoryStatisticsConfig[]>([])
const rows = computed(() => historyStatisticsRows(properties.value, configs.value))
const loading = ref(false)
const busyProperty = ref('')
const rebuildVisible = ref(false)
const rebuildLoading = ref(false)
const rebuildRange = ref<[string, string]>()
const selectedRow = ref<HistoryStatisticsRow>()
const taskVisible = ref(false)
const taskLoading = ref(false)
const cancelLoading = ref(false)
const task = ref<HistoryStatisticsTask>()
let taskTimer: ReturnType<typeof setTimeout> | undefined

const columns = [
  { title: '采集项', key: 'property', width: 230 },
  { title: '配置', key: 'enabled', width: 120 },
  { title: '统计起点', key: 'startTime', width: 185 },
  { title: '已覆盖区间', key: 'coverage', width: 300 },
  { title: '待处理区间', key: 'pending', width: 300 },
  { title: '最近执行', key: 'result', width: 260 },
  { title: '操作', key: 'actions', width: 260 },
]

function requestError(error: unknown, fallback: string): string {
  const value = error as any
  return value?.response?.data?.message || value?.message || fallback
}

async function loadConfigs() {
  if (!productId.value) return
  loading.value = true
  try {
    const response = await queryHistoryStatistics(productId.value)
    if (!response.success) throw new Error(requestError(response, '读取配置失败'))
    configs.value = Array.isArray(response.result) ? response.result : []
  } catch (error) {
    onlyMessage(requestError(error, '读取历史统计配置失败'), 'error')
  } finally {
    loading.value = false
  }
}

async function changeEnabled(row: HistoryStatisticsRow, checked: boolean) {
  if (!productId.value || busyProperty.value) return
  busyProperty.value = row.propertyId
  try {
    const response = await updateHistoryStatistics(productId.value, row.propertyId, checked)
    if (!response.success) throw new Error(requestError(response, '保存配置失败'))
    onlyMessage(checked ? '已启用历史统计' : '已停用历史统计')
    await loadConfigs()
  } catch (error) {
    onlyMessage(requestError(error, '保存历史统计配置失败'), 'error')
  } finally {
    busyProperty.value = ''
  }
}

function openRebuild(row: HistoryStatisticsRow) {
  selectedRow.value = row
  rebuildRange.value = undefined
  rebuildVisible.value = true
}

async function submitRebuild() {
  if (!productId.value || !selectedRow.value) return
  const range = validateRebuildRange(rebuildRange.value)
  if (!range) {
    onlyMessage('请选择有效的北京时间范围：开始早于结束，结束不超过当前时间', 'error')
    return
  }
  rebuildLoading.value = true
  try {
    const response = await rebuildHistoryStatistics(
      productId.value, selectedRow.value.propertyId, range,
    )
    if (!response.success || !response.result?.taskId) {
      throw new Error(requestError(response, '提交回填任务失败'))
    }
    rebuildVisible.value = false
    onlyMessage('历史统计任务已提交')
    await loadConfigs()
    openTask(response.result.taskId)
  } catch (error) {
    onlyMessage(requestError(error, '提交回填任务失败'), 'error')
  } finally {
    rebuildLoading.value = false
  }
}

function isActiveTask(status: string) {
  return status === 'PENDING' || status === 'RUNNING'
}

function taskStatusText(status: string) {
  return ({ PENDING: '待处理', RUNNING: '处理中', SUCCESS: '已完成', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[status] || status
}

function taskColor(status: string) {
  return ({ PENDING: 'default', RUNNING: 'processing', SUCCESS: 'success', FAILED: 'error', CANCELLED: 'warning' } as Record<string, string>)[status] || 'default'
}

const taskPercent = computed(() => {
  const total = task.value?.total || 0
  return total > 0 ? Math.min(100, Math.round(((task.value?.processed || 0) / total) * 100)) : 0
})

function stopTaskPoll() {
  if (taskTimer) clearTimeout(taskTimer)
  taskTimer = undefined
}

function openTask(taskId: string) {
  task.value = { id: taskId, status: 'PENDING' }
  taskVisible.value = true
  refreshTask()
}

async function refreshTask() {
  if (!productId.value || !task.value?.id || !taskVisible.value) return
  stopTaskPoll()
  taskLoading.value = true
  try {
    const response = await queryHistoryStatisticsTask(productId.value, task.value.id)
    if (!response.success || !response.result) throw new Error(requestError(response, '读取任务失败'))
    task.value = response.result
    if (isActiveTask(task.value.status) && taskVisible.value) {
      taskTimer = setTimeout(refreshTask, 5000)
    } else {
      await loadConfigs()
    }
  } catch (error) {
    onlyMessage(requestError(error, '读取历史统计任务失败'), 'error')
  } finally {
    taskLoading.value = false
  }
}

async function cancelTask() {
  if (!productId.value || !task.value?.id) return
  cancelLoading.value = true
  try {
    const response = await cancelHistoryStatisticsTask(productId.value, task.value.id)
    if (!response.success) throw new Error(requestError(response, '取消任务失败'))
    onlyMessage('已请求取消任务，已提交的快照仍会保留')
    await refreshTask()
  } catch (error) {
    onlyMessage(requestError(error, '取消任务失败'), 'error')
  } finally {
    cancelLoading.value = false
  }
}

watch(productId, () => {
  stopTaskPoll()
  taskVisible.value = false
  configs.value = []
  loadConfigs()
}, { immediate: true })

onBeforeUnmount(stopTaskPoll)
</script>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin: 16px 0; }
.secondary { color: var(--app-text-secondary, #8c8c8c); }
.state-text { margin-left: 8px; }
.error-text { display: inline-block; max-width: 220px; color: #cf1322; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; vertical-align: bottom; }
.range-note { margin-top: 8px; }
.task-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 16px; }
</style>
