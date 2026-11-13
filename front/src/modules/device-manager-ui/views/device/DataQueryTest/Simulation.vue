<template>
  <a-card :bordered="false" title="模拟历史数据补录">
    <a-alert type="info" show-icon message="仅补录空白历史区间；已有任意原始数据则拒绝。历史与小时／日／月聚合必须全部完成，任务才会成功。" />
    <a-form layout="vertical" class="simulation-form">
      <a-row :gutter="24">
        <a-col :xs="24" :lg="12"><a-form-item label="单选设备" required><DeviceSelect v-model:value="form.devices" single :disabled="busy" /></a-form-item></a-col>
        <a-col :xs="24" :lg="12"><a-form-item label="数值型采集项" required>
          <a-select v-model:value="form.propertyId" aria-label="数值型采集项" :loading="propertiesLoading" :disabled="busy || !form.devices.length" :options="propertyOptions" placeholder="选择采集项" />
          <small v-if="selected">{{ selected.type }} · 精度 {{ selected.scale }} 位 · {{ selected.unit || '无单位' }}<span v-if="selected.lower != null"> · 下限 {{ selected.lower }}</span><span v-if="selected.upper != null"> · 上限 {{ selected.upper }}</span></small>
        </a-form-item></a-col>
        <a-col :xs="24" :lg="16"><a-form-item label="历史时间范围（北京时间，含开始、不含结束，最长两年）" required>
          <a-range-picker v-model:value="form.range" show-time format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss" :disabled="busy" style="width:100%" />
        </a-form-item></a-col>
        <a-col :xs="24" :lg="8"><a-form-item label="采样间隔" required><a-select v-model:value="form.intervalMinutes" aria-label="采样间隔" :disabled="busy" :options="intervals" /></a-form-item></a-col>
        <a-col :span="24"><a-form-item label="生成方式"><a-radio-group v-model:value="form.mode" :disabled="busy"><a-radio value="RANDOM">限定范围随机</a-radio><a-radio value="INCREMENT">固定递增</a-radio></a-radio-group></a-form-item></a-col>
        <template v-if="form.mode === 'RANDOM'">
          <a-col :span="12"><a-form-item label="最小值" required><a-input v-model:value="form.min" aria-label="最小值" :disabled="busy" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="最大值" required><a-input v-model:value="form.max" aria-label="最大值" :disabled="busy" /></a-form-item></a-col>
        </template>
        <template v-else>
          <a-col :span="12"><a-form-item label="初始值" required><a-input v-model:value="form.initial" aria-label="初始值" :disabled="busy" /></a-form-item></a-col>
          <a-col :span="12"><a-form-item label="每个采样点递增值" required><a-input v-model:value="form.increment" aria-label="递增值" :disabled="busy" /></a-form-item></a-col>
        </template>
      </a-row>
      <a-checkbox v-model:checked="form.snapshots" :disabled="busy || !selected?.snapshotEligible">同时生成累计量小时／日快照</a-checkbox>
      <p class="secondary">勾选时按需为当前设备单独启用采集项统计，并生成该设备的快照。瞬时值的随机模拟不代表真实累计用量。</p>
      <a-alert v-if="error" type="error" show-icon :message="error" />
      <a-descriptions v-if="preview" bordered :column="2" size="small" class="preview">
        <a-descriptions-item label="预计采样数">{{ preview.count }}</a-descriptions-item>
        <a-descriptions-item label="已有原始数据">{{ preview.existing }} 条</a-descriptions-item>
        <a-descriptions-item :label="form.mode === 'RANDOM' ? '随机下限' : '首值'">{{ preview.firstValue }}</a-descriptions-item>
        <a-descriptions-item :label="form.mode === 'RANDOM' ? '随机上限' : '末值'">{{ preview.lastValue }}</a-descriptions-item>
        <a-descriptions-item label="最早可补时间">{{ formatTime(preview.oldestTime) }}</a-descriptions-item>
        <a-descriptions-item label="统计配置">{{ preview.snapshotEnabled ? '已启用' : form.snapshots ? '提交后启用（影响整个产品该采集项）' : '不主动启用' }}</a-descriptions-item>
        <a-descriptions-item label="存储检查" :span="2">{{ layerSummary }}</a-descriptions-item>
      </a-descriptions>
      <a-space class="actions"><a-button :loading="busy" @click="preflight">检查补录参数</a-button><a-button type="primary" :disabled="!canSubmit" :loading="submitting" @click="submit">提交补录任务</a-button></a-space>
    </a-form>
  </a-card>
  <a-card :bordered="false" title="我的补录任务" class="tasks">
    <template #extra><a-button :loading="tasksLoading" @click="loadTasks">刷新</a-button></template>
    <a-alert v-if="taskError" type="error" show-icon :message="taskError" />
    <a-table :data-source="tasks" :columns="columns" row-key="id" :pagination="false" :scroll="{ x: 1050 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'target'">{{ record.request.deviceId }}<br />{{ record.request.propertyId }}</template>
        <template v-else-if="column.key === 'range'">{{ formatTime(record.request.startTime) }}<br />{{ formatTime(record.request.endTime) }}</template>
        <template v-else-if="column.key === 'status'">{{ statusNames[record.status] || record.status }} · {{ stageNames[record.stage] || record.stage }}<br /><span class="error">{{ record.error }}</span></template>
        <template v-else-if="column.key === 'progress'">原始 {{ record.cursor }} / {{ record.total }}<br /><span v-for="(status, layer) in record.layers" :key="layer">{{ stageNames[layer] }}：{{ progressNames[status] }}<br /></span>快照：{{ progressNames[record.snapshotStatus] }}</template>
        <template v-else-if="column.key === 'actions'"><a-space direction="vertical">
          <a-button v-if="record.status === 'FAILED'" type="link" :disabled="busy" @click="retry(record.id)">重试原任务</a-button>
          <a-button v-if="record.status === 'SUCCESS'" type="link" @click="emit('query', record.request, 'query')">带入统计查询</a-button>
          <a-button v-if="record.status === 'SUCCESS'" type="link" @click="emit('query', record.request, 'raw')">查看属性历史</a-button>
        </a-space></template>
      </template>
    </a-table>
    <a-space class="actions"><a-button :disabled="page === 0 || tasksLoading" @click="changePage(-1)">上一页</a-button><span>第 {{ page + 1 }} 页</span><a-button :disabled="tasks.length < 20 || tasksLoading" @click="changePage(1)">下一页</a-button></a-space>
  </a-card>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import DeviceSelect from './DeviceSelect.vue'
import { formatTime } from './utils'
import { simulationRequest, stageNames, statusNames } from './simulationUtils'
import type { SimulationForm } from './simulationUtils'
import { simulationProperties, previewSimulation, submitSimulation, simulationTasks, retrySimulation } from '../../../api/simulation'
import type { SimulationProperty, SimulationPreview, SimulationRequest, SimulationTask } from '../../../api/simulation'
const emit = defineEmits<{ (e: 'query', request: SimulationRequest, target: 'query' | 'raw'): void }>()
const form = reactive<SimulationForm>({ devices: [], propertyId: '', intervalMinutes: 15, mode: 'RANDOM', min: '0', max: '100', initial: '0', increment: '1', snapshots: false })
const properties = ref<SimulationProperty[]>([]), propertiesLoading = ref(false), busy = ref(false), submitting = ref(false)
const preview = ref<SimulationPreview>(), error = ref(''), tasks = ref<SimulationTask[]>([]), taskError = ref(''), tasksLoading = ref(false), page = ref(0)
const newRequestId = () => Array.from(crypto.getRandomValues(new Uint8Array(16)), b => b.toString(16).padStart(2, '0')).join('')
let requestId = newRequestId(), generation = 0, disposed = false, timer: ReturnType<typeof setTimeout> | undefined
const selected = computed(() => properties.value.find(p => p.propertyId === form.propertyId))
const propertyOptions = computed(() => properties.value.map(p => ({ value: p.propertyId, label: `${p.name || p.propertyId}（${p.propertyId}）· ${p.type} ${p.unit || ''}` })))
const intervals = [5, 15, 30, 60].map(value => ({ value, label: value === 60 ? '1 小时' : `${value} 分钟` }))
const canSubmit = computed(() => !!preview.value && preview.value.existing === 0 && preview.value.canInitialize && !busy.value)
const layerSummary = computed(() => Object.entries(preview.value?.layers || {}).map(([k, v]) => `${stageNames[k]}：${v === 'READY' ? '就绪' : v === 'MISSING' ? '提交时初始化' : '结构不兼容'}`).join('；'))
const progressNames: Record<string, string> = { PENDING: '待处理', RUNNING: '处理中', DONE: '已完成', NOT_REQUESTED: '未勾选' }
const columns = [{ title: '设备／采集项', key: 'target' }, { title: '时间范围', key: 'range' }, { title: '状态', key: 'status' }, { title: '进度', key: 'progress' }, { title: '操作', key: 'actions' }]
const message = (e: any) => e?.response?.data?.message || e?.message || '请求失败'
watch(form, () => { preview.value = undefined; error.value = ''; requestId = newRequestId() }, { deep: true })
watch(() => form.devices[0], async id => {
  const current = ++generation; properties.value = []; form.propertyId = ''; form.snapshots = false
  if (!id) { propertiesLoading.value = false; return }
  propertiesLoading.value = true
  try { const r = await simulationProperties(id); if (!r.success) throw new Error('采集项加载失败'); if (!disposed && current === generation) properties.value = r.result || [] }
  catch (e) { if (!disposed && current === generation) error.value = message(e) }
  finally { if (!disposed && current === generation) propertiesLoading.value = false }
})
watch(selected, p => { if (!p?.snapshotEligible) form.snapshots = false })
async function preflight() {
  busy.value = true; error.value = ''; preview.value = undefined
  try { const r = await previewSimulation(simulationRequest(form, requestId)); if (!r.success) throw new Error('预检失败'); if (!disposed) preview.value = r.result }
  catch (e) { if (!disposed) error.value = message(e) } finally { if (!disposed) busy.value = false }
}
async function submit() {
  if (!canSubmit.value) return
  busy.value = true; submitting.value = true; error.value = ''
  try { const r = await submitSimulation(simulationRequest(form, requestId)); if (!r.success) throw new Error('提交失败'); preview.value = undefined; page.value = 0; await loadTasks() }
  catch (e) { error.value = message(e) } finally { busy.value = false; submitting.value = false }
}
async function loadTasks() {
  if (disposed || tasksLoading.value) return
  tasksLoading.value = true
  try { const r = await simulationTasks(page.value); if (!r.success) throw new Error('任务加载失败'); if (!disposed) { tasks.value = r.result || []; taskError.value = '' } }
  catch (e) { if (!disposed) taskError.value = message(e) } finally { if (!disposed) tasksLoading.value = false }
}
async function retry(id: string) { busy.value = true; try { const r = await retrySimulation(id); if (!r.success) throw new Error('重试失败'); await loadTasks() } catch (e) { taskError.value = message(e) } finally { busy.value = false } }
async function poll() { await loadTasks(); if (!disposed) timer = setTimeout(poll, 3000) }
function changePage(delta: number) { page.value += delta; loadTasks() }
onMounted(poll)
onBeforeUnmount(() => { disposed = true; generation++; clearTimeout(timer) })
</script>
<style scoped lang="less">
.simulation-form, .preview, .actions, .tasks { margin-top: 20px; }
.secondary, small { color: @text-color-secondary; }
.error { color: @error-color; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
