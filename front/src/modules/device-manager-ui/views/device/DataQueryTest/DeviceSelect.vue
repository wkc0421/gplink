<template>
  <a-select
    :value="selectedOptions"
    mode="multiple"
    label-in-value
    show-search
    allow-clear
    :auto-clear-search-value="false"
    :filter-option="false"
    :options="options"
    :loading="fetching"
    :disabled="disabled"
    :list-item-height="48"
    :list-height="288"
    :max-tag-count="'responsive'"
    :max-tag-text-length="36"
    aria-label="选择设备"
    placeholder="选择设备，可按设备名称或 ID 搜索"
    class="device-select"
    @change="changeSelection"
    @search="search"
    @dropdown-visible-change="openDropdown"
    @popup-scroll="loadMore"
  >
    <template #option="option">
      <div class="device-name">{{ option.name || option.value }}</div>
      <div class="device-description">{{ option.value }}<template v-if="option.productName"> · {{ option.productName }}</template></div>
    </template>
    <template #notFoundContent>
      <a-spin v-if="fetching" size="small" />
      <span v-else>{{ loadError ? '设备加载失败，请重试' : '没有匹配的设备' }}</span>
    </template>
  </a-select>
  <div class="device-selection-info">
    <span>已选择 {{ value.length }} / 500 个设备</span>
    <a-button v-if="loadError" type="link" size="small" :disabled="disabled || fetching" @click="retry">加载失败，点击重试</a-button>
    <span v-else-if="fetching">正在加载设备…</span>
    <span v-else-if="hasMore">下拉列表滚动到底部可继续加载</span>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { LabeledValue, SelectValue } from 'ant-design-vue/es/select'
import { queryDeviceOptions } from '../../../api/legacyDeviceData'
import type { QueryDeviceOption } from '../../../api/legacyDeviceData'

const props = defineProps<{ value: string[]; disabled?: boolean }>()
const emit = defineEmits<{ (event: 'update:value', value: string[]): void }>()
const devices = ref<QueryDeviceOption[]>([])
const selectedDevices = ref(new Map<string, QueryDeviceOption>())
const fetching = ref(false)
const loadError = ref(false)
const hasMore = ref(false)
let loaded = false
let keyword = ''
let pageIndex = -1
let generation = 0
let disposed = false
let searchTimer: ReturnType<typeof setTimeout> | undefined

const deviceLabel = (device: QueryDeviceOption) => device.name ? `${device.name}（${device.id}）` : device.id
const selectedOptions = computed(() => props.value.map(id => ({ value: id, label: deviceLabel(selectedDevices.value.get(id) || { id }) })))
const options = computed(() => devices.value.map(device => ({
  value: device.id,
  label: deviceLabel(device),
  name: device.name,
  productName: device.productName,
  disabled: props.value.length >= 500 && !props.value.includes(device.id),
})))

function changeSelection(selected: SelectValue) {
  const selection = (Array.isArray(selected) ? selected : []) as LabeledValue[]
  const ids = [...new Set(selection.map(item => String(item.value)))].slice(0, 500)
  const candidates = new Map(devices.value.map(device => [device.id, device]))
  selectedDevices.value = new Map(ids.map(id => [id, candidates.get(id) || selectedDevices.value.get(id) || { id }]))
  emit('update:value', ids)
}

async function loadPage(nextPage: number, requestGeneration = generation) {
  fetching.value = true
  loadError.value = false
  try {
    const response = await queryDeviceOptions(keyword, nextPage)
    if (disposed || requestGeneration !== generation) return
    if (!response.success || !Array.isArray(response.result?.data)) throw new Error('Device list query failed')
    const data = response.result.data
    devices.value = [...new Map([
      ...(nextPage === 0 ? [] : devices.value), ...data,
    ].map(device => [device.id, device])).values()]
    pageIndex = nextPage
    hasMore.value = data.length > 0 && (nextPage + 1) * 50 < response.result.total
    loaded = true
  } catch {
    if (!disposed && requestGeneration === generation) loadError.value = true
  } finally {
    if (!disposed && requestGeneration === generation) fetching.value = false
  }
}

function search(value: string) {
  clearTimeout(searchTimer)
  keyword = value.trim()
  const requestGeneration = ++generation
  devices.value = []
  pageIndex = -1
  hasMore.value = false
  loadError.value = false
  fetching.value = true
  searchTimer = setTimeout(() => loadPage(0, requestGeneration), 300)
}

function openDropdown(open: boolean) {
  if (open && !loaded && !fetching.value) loadPage(0)
}

function loadMore(event: UIEvent) {
  const list = event.target as HTMLElement
  if (!props.disabled && !fetching.value && !loadError.value && hasMore.value && list.scrollTop + list.clientHeight >= list.scrollHeight - 24) loadPage(pageIndex + 1)
}

function retry() { if (!props.disabled && !fetching.value) loadPage(pageIndex + 1) }
onBeforeUnmount(() => { disposed = true; generation++; clearTimeout(searchTimer) })
</script>

<style scoped lang="less">
.device-select { width: 100%; }
.device-name { overflow: hidden; text-overflow: ellipsis; line-height: 20px; }
.device-description, .device-selection-info { color: @text-color-secondary; font-size: 12px; }
.device-description { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; line-height: 18px; }
.device-selection-info { display: flex; flex-wrap: wrap; justify-content: space-between; align-items: center; gap: 4px 12px; margin-top: 6px; }
</style>
