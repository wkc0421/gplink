<template>
  <div>
    <label class="target-label" :for="inputId">{{ label }}</label>
    <a-select :id="inputId" :value="selection" mode="multiple" label-in-value show-search allow-clear :filter-option="false" :options="options" :loading="loading" :disabled="disabled" :max-tag-count="'responsive'" :aria-label="label" :placeholder="`${label}，按名称或 ID 搜索`" class="target-select" @change="change" @search="search" @dropdown-visible-change="open" @popup-scroll="more">
      <template #option="option"><div>{{ option.name }}</div><small>{{ option.value }}<template v-if="option.productName"> · {{ option.productName }}</template></small></template>
      <template #notFoundContent><a-spin v-if="loading" size="small" /><span v-else>{{ error ? '加载失败' : '暂无匹配项' }}</span></template>
    </a-select>
    <a-button v-if="error" type="link" size="small" @click="fetchPage(page + 1)">加载失败，点击重试</a-button>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { request } from '@jetlinks-web/core'
import type { LabeledValue, SelectValue } from 'ant-design-vue/es/select'

const props = defineProps<{ value: string[]; kind: 'PRODUCT' | 'DEVICE'; productIds?: string[]; disabled?: boolean; label: string }>()
const emit = defineEmits<{ (event: 'update:value', value: string[]): void }>()
const inputId = computed(() => `history-target-${props.label}`)
type Target = { id: string; name?: string; productName?: string }
const rows = ref<Target[]>([]), cache = ref(new Map<string, Target>())
const loading = ref(false), error = ref(false), hasMore = ref(false)
let keyword = '', page = -1, generation = 0, loaded = false, disposed = false
let timer: ReturnType<typeof setTimeout> | undefined
const labelFor = (row: Target) => row.name ? `${row.name}（${row.id}）` : row.id
const selection = computed(() => props.value.map(id => ({ value: id, label: labelFor(cache.value.get(id) || { id }) })))
const options = computed(() => rows.value.map(row => ({ value: row.id, name: row.name || row.id, productName: row.productName, label: labelFor(row), disabled: props.value.length >= 500 && !props.value.includes(row.id) })))
function change(value: SelectValue) {
  const values = (Array.isArray(value) ? value : value ? [value] : []) as LabeledValue[]
  const ids = [...new Set(values.map(item => String(item.value)))].slice(0, 500)
  emit('update:value', ids)
}
async function fetchPage(next: number, expected = generation) {
  loading.value = true; error.value = false
  try {
    const terms: any[] = []
    if (keyword) terms.push({ terms: [{ column: 'name', termType: 'like', value: `%${keyword}%` }, { column: 'id', termType: 'like', value: `%${keyword}%`, type: 'or' }] })
    if (props.kind === 'DEVICE' && props.productIds?.length) terms.push({ column: 'productId', termType: 'in', value: props.productIds })
    const response = await request.post<{ data: Target[]; total: number }>(props.kind === 'PRODUCT' ? '/device-product/_query' : '/device-instance/_query', { pageIndex: next, pageSize: 50, sorts: [{ name: 'name', order: 'asc' }, { name: 'id', order: 'asc' }], terms }, { hiddenError: true })
    if (disposed || generation !== expected) return
    if (!response.success || !Array.isArray(response.result?.data)) throw new Error('query failed')
    rows.value = [...new Map([...(next ? rows.value : []), ...response.result.data].map(row => [row.id, row])).values()]
    rows.value.forEach(row => cache.value.set(row.id, row))
    page = next; loaded = true; hasMore.value = response.result.data.length > 0 && (next + 1) * 50 < response.result.total
  } catch { if (!disposed && expected === generation) error.value = true }
  finally { if (!disposed && expected === generation) loading.value = false }
}
function reset() { generation++; page = -1; rows.value = []; loaded = false; hasMore.value = false; error.value = false; loading.value = false; clearTimeout(timer) }
function search(value: string) { keyword = value.trim(); reset(); loading.value = true; const expected = generation; timer = setTimeout(() => fetchPage(0, expected), 300) }
function open(value: boolean) { if (value && !loaded && !loading.value) fetchPage(0) }
function more(event: UIEvent) { const list = event.target as HTMLElement; if (!loading.value && !error.value && hasMore.value && list.scrollTop + list.clientHeight >= list.scrollHeight - 24) fetchPage(page + 1) }
watch(() => [props.kind, JSON.stringify(props.productIds)], () => { keyword = ''; reset() })
onBeforeUnmount(() => { disposed = true; reset() })
</script>

<style scoped>
.target-select { width: 100%; min-width: 220px; }
.target-label { display:block;margin-bottom:4px;font-size:12px;color:var(--app-text-secondary,#777); }
small { color: var(--app-text-secondary, #777); }
</style>
