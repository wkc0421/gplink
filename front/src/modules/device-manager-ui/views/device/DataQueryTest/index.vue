<template>
  <j-page-container>
    <a-card :bordered="false" title="设备数据接口测试">
      <template #extra><a-button @click="goBack">返回设备列表</a-button></template>
      <a-tabs v-model:active-key="form.mode">
        <a-tab-pane key="aggregation" tab="聚合查询" :disabled="loading" />
        <a-tab-pane key="interval" tab="区间查询" :disabled="loading" />
        <a-tab-pane key="history" tab="区间历史" :disabled="loading" />
      </a-tabs>
      <a-form :model="form" layout="vertical" @finish="executeQuery">
        <a-row :gutter="24">
          <a-col :xs="24" :lg="12">
            <a-form-item label="选择设备" required>
              <DeviceSelect :key="deviceSelectionVersion" v-model:value="form.devices" :disabled="loading" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :lg="12">
            <a-form-item label="属性 ID" required extra="每次查询一个属性，按物模型中的属性 ID 填写。">
              <a-input v-model:value="form.property" aria-label="属性 ID" placeholder="例如 E" :disabled="loading" />
            </a-form-item>
          </a-col>
          <a-col :xs="24" :lg="12">
            <a-form-item label="时间范围（北京时间）" required>
              <a-range-picker v-model:value="form.range" show-time format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss" :placeholder="['开始时间', '结束时间']" :disabled="loading" class="full-width" />
            </a-form-item>
          </a-col>
          <template v-if="form.mode === 'aggregation'">
            <a-col :xs="12" :lg="4">
              <a-form-item label="聚合函数" required>
                <a-select v-model:value="form.aggregation" aria-label="聚合函数" :options="aggregationOptions" :disabled="loading" />
              </a-form-item>
            </a-col>
            <a-col :xs="12" :lg="4">
              <a-form-item label="聚合间隔" required>
                <a-select v-model:value="form.interval" aria-label="聚合间隔" :options="aggregationIntervalOptions" :disabled="loading" />
              </a-form-item>
            </a-col>
            <a-col :xs="24" :lg="4">
              <a-form-item label="每台设备结果上限" required>
                <a-input-number v-model:value="form.limit" aria-label="每台设备结果上限" :min="1" :max="1000" :precision="0" :disabled="loading" class="full-width" />
              </a-form-item>
            </a-col>
          </template>
          <a-col v-else-if="form.mode === 'history'" :xs="24" :lg="12">
            <a-form-item label="区间类型" required extra="day 按小时、month 按天、year 按月；week 按自然周、quarter 按自然季度，保留请求首尾；最多返回 1000 段。">
              <a-select v-model:value="form.historyType" aria-label="区间类型" :options="historyTypeOptions" :disabled="loading" />
            </a-form-item>
          </a-col>
        </a-row>
        <div class="query-actions">
          <a-button type="primary" html-type="submit" :loading="loading">发送查询</a-button>
          <a-button :disabled="loading" @click="resetForm">重置</a-button>
          <span class="secondary">时间为 Asia/Shanghai，表格展示接口实际返回的时间和值。</span>
        </div>
      </a-form>
      <a-collapse ghost class="request-preview">
        <a-collapse-panel key="request" header="请求预览">
          <template v-if="preview">
            <div class="endpoint"><a-tag color="blue">POST</a-tag><code>{{ requestUrl(preview) }}</code></div>
            <pre>{{ JSON.stringify(preview.body, null, 2) }}</pre>
          </template>
          <span v-else class="secondary">{{ validationMessage }}</span>
        </a-collapse-panel>
      </a-collapse>
    </a-card>

    <a-card :bordered="false" title="查询结果" class="result-card">
      <template #extra>
        <a-space v-if="completed">
          <a-tag :color="errorMessage ? 'error' : 'success'">{{ errorMessage ? '失败' : '成功' }}</a-tag>
          <span v-if="responseStatus !== undefined">状态 {{ responseStatus }}</span>
          <span>{{ elapsed }} ms · {{ rows.length }} 条</span>
        </a-space>
      </template>
      <a-alert v-if="errorMessage" type="error" show-icon :message="errorMessage" class="error-message" />
      <div v-if="submittedUrl" class="endpoint secondary">POST {{ submittedUrl }}</div>
      <a-table :columns="columns" :data-source="rows" row-key="key" :loading="loading" size="middle" :scroll="{ x: resultMode === 'aggregation' ? 800 : 1300 }" :pagination="{ pageSize: 20, showSizeChanger: true, pageSizeOptions: ['20', '50', '100'], showTotal: (total: number) => `共 ${total} 条` }">
        <template #bodyCell="{ column, record }">
          <template v-if="timeKeys.has(column.dataIndex)">{{ formatTime(record[column.dataIndex]) }}</template>
          <template v-else>{{ displayValue(record[column.dataIndex]) }}</template>
        </template>
        <template #emptyText>{{ completed ? (errorMessage ? '查询失败，请查看错误信息' : '该时间范围内没有数据') : '填写参数后发送查询' }}</template>
      </a-table>
      <p class="secondary value-note">0 为接口返回的数值零，null 为显式空值，— 表示字段未返回。</p>
      <a-collapse v-if="completed" ghost>
        <a-collapse-panel key="response" header="原始响应">
          <pre>{{ JSON.stringify(rawResponse, null, 2) }}</pre>
        </a-collapse-panel>
      </a-collapse>
    </a-card>
  </j-page-container>
</template>

<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { useMenuStore } from '@/store/menu'
import { BASE_API } from '@jetlinks-web/constants'
import { queryLegacyDeviceData } from '../../../api/legacyDeviceData'
import type { DeviceDataQuery, DeviceDataQueryMode } from '../../../api/legacyDeviceData'
import {
  aggregationIntervalOptions, aggregationOptions, buildQuery, displayValue,
  formatTime, historyTypeOptions, resultRows,
} from './utils'
import type { QueryForm, ResultRow } from './utils'
import DeviceSelect from './DeviceSelect.vue'

const menuStore = useMenuStore()
function initialForm(): QueryForm {
  const now = formatTime(Date.now())
  return { mode: 'aggregation', devices: [], property: '', range: [`${now.slice(0, 10)} 00:00:00`, now], aggregation: 'LAST', interval: '1h', limit: 100, historyType: 'day' }
}
const form = reactive<QueryForm>(initialForm())
const deviceSelectionVersion = ref(0)
const loading = ref(false)
const completed = ref(false)
const rows = ref<ResultRow[]>([])
const rawResponse = ref<unknown>()
const errorMessage = ref('')
const responseStatus = ref<number>()
const elapsed = ref(0)
const submittedUrl = ref('')
const resultMode = ref<DeviceDataQueryMode>('aggregation')
const timeKeys = new Set(['time', 'startTime', 'endTime'])
const draft = computed(() => {
  try { return { query: buildQuery(form), error: '' } }
  catch (error) { return { query: undefined, error: (error as Error).message } }
})
const preview = computed(() => draft.value.query)
const validationMessage = computed(() => draft.value.error)
const columns = computed(() => [
  { title: '设备 ID', dataIndex: 'deviceId', width: 200 },
  { title: '属性 ID', dataIndex: 'property', width: 140 },
  ...(resultMode.value === 'aggregation' ? [
    { title: '时间（北京时间）', dataIndex: 'time', width: 200 },
    { title: '聚合值', dataIndex: 'value', width: 180 },
  ] : [
    { title: '开始时间（北京时间）', dataIndex: 'startTime', width: 200 },
    { title: '结束时间（北京时间）', dataIndex: 'endTime', width: 200 },
    { title: '开始值', dataIndex: 'startValue', width: 180 },
    { title: '结束值', dataIndex: 'endValue', width: 180 },
  ]),
])

function requestUrl(query: DeviceDataQuery) {
  const suffix = query.params ? `?startTime=${query.params.startTime}&endTime=${query.params.endTime}` : ''
  return `${BASE_API}/v1/device-data${query.path}${suffix}`
}

function clearResult() {
  completed.value = false
  rows.value = []
  rawResponse.value = undefined
  errorMessage.value = ''
  responseStatus.value = undefined
  submittedUrl.value = ''
}
watch(() => form.mode, () => { clearResult(); resultMode.value = form.mode })

async function executeQuery() {
  if (loading.value) return
  clearResult()
  let query: DeviceDataQuery
  try { query = buildQuery(form) }
  catch (error) { errorMessage.value = (error as Error).message; return }
  resultMode.value = form.mode
  submittedUrl.value = requestUrl(query)
  loading.value = true
  const started = performance.now()
  try {
    const response = await queryLegacyDeviceData(query)
    rawResponse.value = response
    responseStatus.value = response.status
    if (!response.success) throw new Error(response.message || `查询失败（状态 ${response.status}）`)
    rows.value = resultRows(resultMode.value, response.result, query.body.properties[0])
  } catch (error: unknown) {
    const failure = error as { message?: string; response?: { status?: number; data?: { message?: string } }; code?: string }
    if (failure.response) {
      responseStatus.value = failure.response.status
      rawResponse.value = failure.response.data
    }
    errorMessage.value = failure.code === 'ECONNABORTED'
      ? '查询超时，请缩小时间范围或减少设备数量'
      : failure.response?.data?.message || failure.message || '查询失败，请检查网络连接'
    if (rawResponse.value === undefined) rawResponse.value = { message: errorMessage.value }
  } finally {
    elapsed.value = Math.round(performance.now() - started)
    completed.value = true
    loading.value = false
  }
}

function resetForm() { Object.assign(form, initialForm()); deviceSelectionVersion.value++; clearResult() }
function goBack() { menuStore.jumpPage('device/Instance', {}) }
</script>

<style scoped lang="less">
.full-width { width: 100%; }
.query-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 12px; }
.secondary { color: @text-color-secondary; }
.request-preview { margin-top: 16px; }
.result-card { margin-top: 16px; }
.endpoint { margin-bottom: 12px; overflow-wrap: anywhere; }
.error-message { margin-bottom: 16px; }
.value-note { margin: 12px 0 0; }
pre { max-height: 360px; overflow: auto; padding: 12px; margin: 0; background: @background-color-base; color: @text-color; border: 1px solid @border-color-split; border-radius: 4px; white-space: pre-wrap; overflow-wrap: anywhere; }
</style>
