<template>
  <a-card title="属性原始历史" :bordered="false">
    <a-form layout="vertical">
      <a-form-item label="设备"><DeviceSelect v-model:value="devices" single :disabled="loading" /></a-form-item>
      <a-form-item label="属性 ID"><a-input v-model:value="property" :disabled="loading" /></a-form-item>
      <a-form-item label="北京时间范围（含开始、不含结束）"><a-range-picker v-model:value="range" show-time format="YYYY-MM-DD HH:mm:ss" value-format="YYYY-MM-DD HH:mm:ss" :disabled="loading" /></a-form-item>
      <a-button type="primary" :loading="loading" @click="query(0)">查询历史</a-button>
    </a-form>
    <a-alert v-if="error" type="error" :message="error" />
    <a-table :loading="loading" :data-source="rows" :columns="columns" :pagination="false" :row-key="(r: any) => `${r.property}:${r.timestamp}`">
      <template #bodyCell="{ column, record }"><template v-if="column.dataIndex === 'timestamp'">{{ formatTime(record.timestamp) }}</template><template v-else>{{ displayValue(record[String(column.dataIndex)]) }}</template></template>
    </a-table>
    <a-space><a-button :disabled="loading || page === 0 || !queried" @click="query(page - 1)">上一页</a-button><span>第 {{ page + 1 }} 页，共 {{ total }} 条（每页100条）</span><a-button :disabled="loading || !queried || (page + 1) * 100 >= total" @click="query(page + 1)">下一页</a-button></a-space>
  </a-card>
</template>
<script setup lang="ts">
import { ref, watch } from 'vue'
import { request } from '@jetlinks-web/core'
import DeviceSelect from './DeviceSelect.vue'
import { formatTime, parseTime, displayValue } from './utils'
import type { SimulationRequest } from '../../../api/simulation'
const props = defineProps<{ initial?: SimulationRequest }>()
const devices = ref<string[]>(props.initial ? [props.initial.deviceId] : []), property = ref(props.initial?.propertyId || '')
const range = ref<[string, string] | undefined>(props.initial ? [formatTime(props.initial.startTime), formatTime(props.initial.endTime)] : undefined)
const rows = ref<any[]>([]), total = ref(0), page = ref(0), loading = ref(false), queried = ref(false), error = ref('')
const columns = [{ title: '采样时间', dataIndex: 'timestamp' }, { title: '属性', dataIndex: 'property' }, { title: '值', dataIndex: 'value' }]
watch([devices, property, range], () => { rows.value = []; total.value = 0; page.value = 0; queried.value = false }, { deep: true })
async function query(next: number) {
  error.value = ''
  const start = parseTime(range.value?.[0] || ''), end = parseTime(range.value?.[1] || '')
  if (devices.value.length !== 1 || !property.value.trim() || start === undefined || end === undefined || end <= start) { error.value = '请选择一个设备、属性和有效时间范围'; return }
  loading.value = true
  try {
    const r = await request.post<{ data: any[]; total: number }>(`/v1/device-data/${encodeURIComponent(devices.value[0])}/${encodeURIComponent(property.value.trim())}/history/pg`, {
      pageIndex: next, pageSize: 100, sorts: [{ name: 'timestamp', order: 'asc' }], terms: [
        { column: 'timestamp', termType: 'gte', value: start }, { column: 'timestamp', termType: 'lt', value: end },
      ],
    })
    if (!r.success) throw new Error('查询失败')
    rows.value = r.result?.data || []; total.value = r.result?.total || 0; page.value = next; queried.value = true
  } catch (e: any) { error.value = e?.response?.data?.message || e.message || '查询失败' } finally { loading.value = false }
}
</script>
