<template>
  <a-modal v-model:open="rebuildOpen" title="历史回填 / 范围重算" ok-text="提交任务" :confirm-loading="saving" :mask-closable="false" @ok="submit">
    <p>{{ target ? historyTargetName(target) : '' }} · {{ target?.propertyName }}（{{ target?.propertyId }}）</p>
    <a-alert type="info" show-icon message="仅重算当前对象所选采集项的小时、日边界。时间为北京时间，取消任务不会清除已完成的数据。" />
    <a-range-picker v-model:value="range" show-time format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss" :placeholder="['开始时间', '结束时间']" style="width:100%;margin-top:16px" />
    <a-alert v-if="error" type="error" :message="error" style="margin-top:12px" />
  </a-modal>
  <a-modal v-model:open="taskOpen" title="历史统计任务" :footer="null" @cancel="stop">
    <p>{{ target ? historyTargetName(target) : '' }} · {{ target?.propertyName }}（{{ target?.propertyId }}）</p>
    <a-alert v-if="error" type="error" :message="error" />
    <a-spin :spinning="loading">
      <a-descriptions v-if="task" :column="1" size="small">
        <a-descriptions-item label="任务 ID">{{ task.id }}</a-descriptions-item>
        <a-descriptions-item label="状态">{{ statusText(task.status) }}</a-descriptions-item>
        <a-descriptions-item label="进度">{{ task.processed ?? 0 }} / {{ task.total ?? '—' }}</a-descriptions-item>
        <a-descriptions-item v-if="task.lastError" label="错误">{{ task.lastError }}</a-descriptions-item>
      </a-descriptions>
      <a-progress :percent="percent" :status="task?.status === 'FAILED' ? 'exception' : task?.status === 'SUCCESS' ? 'success' : 'active'" />
    </a-spin>
    <div class="task-actions"><a-button @click="refresh">刷新任务</a-button><a-button v-if="canUpdate && task && active(task.status)" danger :loading="saving" @click="cancel">取消后续处理</a-button></div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { cancelManagedHistoryTask, queryManagedHistoryTask, rebuildManagedHistory } from '../../../api/historyStatistics'
import type { HistoryManagementRow, HistoryStatisticsTask } from '../../../api/historyStatistics'
import { validateRebuildRange } from '../Product/Detail/HistoryStatistics/utils'
import { errorText, historyTargetName, resultValue } from './utils'
const props = defineProps<{ canUpdate: boolean }>()
const emit = defineEmits<{ (event: 'changed'): void }>()
const target = ref<HistoryManagementRow>(), task = ref<HistoryStatisticsTask>()
const rebuildOpen = ref(false), taskOpen = ref(false), saving = ref(false), loading = ref(false), error = ref('')
const range = ref<[string, string]>()
let timer: ReturnType<typeof setTimeout> | undefined
let generation = 0
const active = (status: string) => status === 'PENDING' || status === 'RUNNING'
const statusText = (status: string) => ({ PENDING: '待处理', RUNNING: '处理中', SUCCESS: '已完成', FAILED: '失败', CANCELLED: '已取消' } as Record<string, string>)[status] || status
const percent = computed(() => task.value?.total ? Math.min(100, Math.round(100 * (task.value.processed || 0) / task.value.total)) : 0)
function stop() { clearTimeout(timer); generation++ }
function rebuild(row: HistoryManagementRow) { stop(); taskOpen.value = false; target.value = row; error.value = ''; range.value = undefined; rebuildOpen.value = true }
function showTask(row: HistoryManagementRow, taskId = row.config?.taskId) { if (!taskId) return; stop(); target.value = row; error.value = ''; task.value = { id: taskId, status: 'PENDING' }; taskOpen.value = true; refresh() }
async function submit() {
  if (!props.canUpdate || saving.value) return
  const checked = validateRebuildRange(range.value)
  if (!checked) { error.value = '请选择有效的北京时间范围：开始早于结束，结束不超过当前时间'; return }
  if (!target.value) return
  saving.value = true; error.value = ''
  const row = target.value
  try { const result = resultValue(await rebuildManagedHistory(row, checked), '提交任务失败'); rebuildOpen.value = false; emit('changed'); showTask(row, result.taskId) }
  catch (e) { error.value = errorText(e) }
  finally { saving.value = false }
}
async function refresh() {
  if (!target.value || !task.value || !taskOpen.value) return
  clearTimeout(timer)
  const expected = ++generation, row = target.value, id = task.value.id
  loading.value = true; error.value = ''
  try {
    const value = resultValue(await queryManagedHistoryTask(row, id), '读取任务失败')
    if (expected !== generation || !taskOpen.value) return
    task.value = value
    if (active(value.status)) timer = setTimeout(refresh, 5000)
    else emit('changed')
  } catch (e) { if (expected === generation) error.value = errorText(e) }
  finally { if (expected === generation) loading.value = false }
}
async function cancel() {
  if (!props.canUpdate || saving.value || !target.value || !task.value) return
  saving.value = true; error.value = ''
  try { resultValue(await cancelManagedHistoryTask(target.value, task.value.id), '取消失败'); await refresh() }
  catch (e) { error.value = errorText(e) }
  finally { saving.value = false }
}
onBeforeUnmount(stop)
defineExpose({ rebuild, showTask, close: () => { stop(); taskOpen.value = false; rebuildOpen.value = false } })
</script>

<style scoped>.task-actions { display:flex;justify-content:flex-end;gap:8px;margin-top:16px; }</style>
