<template>
  <j-page-container>
    <div class="latest-device-data">
      <div class="toolbar">
        <div class="product-filter">
          <label for="latest-device-product">选择产品</label>
          <a-select
            id="latest-device-product"
            v-model:value="selectedProduct"
            placeholder="按产品名称或 ID 搜索"
            show-search
            allow-clear
            label-in-value
            :filter-option="false"
            :options="productOptions"
            :loading="productsLoading"
            @dropdown-visible-change="openProducts"
            @search="searchProducts"
            @popup-scroll="loadMoreProducts"
            @change="changeProduct"
          >
            <template #notFoundContent>
              <a-spin v-if="productsLoading" size="small" />
              <span v-else>{{ productsError ? '读取产品失败' : '暂无匹配产品' }}</span>
            </template>
          </a-select>
          <a-button v-if="productsError" type="link" @click="loadProducts">重新加载产品</a-button>
        </div>
        <a-space wrap>
          <a-button :disabled="!productId" :loading="loading" @click="loadPage">刷新</a-button>
          <a-button type="primary" :disabled="!productId" :loading="exporting" @click="exportAll">导出全部</a-button>
        </a-space>
      </div>

      <a-alert v-if="error" type="error" show-icon :message="error">
        <template #action><a-button size="small" @click="loadPage">重试</a-button></template>
      </a-alert>
      <a-table
        row-key="deviceId"
        size="middle"
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="productId && !error ? pagination : false"
        :scroll="{ x: tableWidth }"
        @change="changePage"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'device'">
            <div>{{ record.deviceName || record.deviceId }}</div>
            <small>{{ record.deviceId }}</small>
          </template>
          <template v-else-if="column.key === 'latestTimestamp'">
            {{ formatShanghaiTime(record.latestTimestamp) }}
          </template>
          <template v-else>
            <span :title="propertyTitle(record.values.get(column.dataIndex))" class="property-value">
              {{ formatValue(record.values.get(column.dataIndex)?.value) }}
            </span>
          </template>
        </template>
        <template #emptyText>
          {{ !productId ? '请先选择产品' : error || loading ? '—' : '该产品暂无设备' }}
        </template>
      </a-table>
    </div>
  </j-page-container>
</template>

<script setup lang="ts" name="LatestDeviceData">
import { computed, onBeforeUnmount, ref } from 'vue'
import type { TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { onlyMessage } from '@jetlinks-web/utils'
import { exportLatestDeviceData, queryLatestDeviceData } from '../../../api/latestDeviceData'
import type { LatestDevicePage, LatestDeviceRow, LatestPropertyValue } from '../../../api/latestDeviceData'
import { queryProductList } from '../../../api/product'
import { formatShanghaiTime } from '../Product/Detail/HistoryStatistics/utils'

interface ProductOption {
  value: string
  label: string
}

const selectedProduct = ref<ProductOption>()
const productId = computed(() => selectedProduct.value?.value)
const productOptions = ref<ProductOption[]>([])
const productsLoading = ref(false)
const productsError = ref(false)
const productPageSize = 50
let productPage = 0
let productsTotal = 0
let productKeyword = ''
let productSearchGeneration = 0
let productSearchTimer: ReturnType<typeof setTimeout> | undefined
let productLoaded = false

async function loadProducts() {
  if (productsLoading.value) return
  const generation = productSearchGeneration
  const value = `%${productKeyword}%`
  productsLoading.value = true
  productsError.value = false
  try {
    const response = await queryProductList({
      pageIndex: productPage,
      pageSize: productPageSize,
      sorts: [{ name: 'name', order: 'asc' }, { name: 'id', order: 'asc' }],
      terms: productKeyword ? [{ terms: [
        { column: 'name', termType: 'like', value },
        { column: 'id', termType: 'like', value, type: 'or' },
      ] }] : [],
    })
    if (generation !== productSearchGeneration) return
    const result = response.result as { data: Array<{ id: string; name?: string }>; total: number } | undefined
    if (!response.success || !Array.isArray(result?.data)) throw new Error('读取产品失败')
    const options = new Map(productOptions.value.map(option => [option.value, option]))
    for (const { id, name } of result.data) {
      options.set(id, { value: id, label: name ? `${name}（${id}）` : id })
    }
    productOptions.value = [...options.values()]
    productsTotal = result.data.length ? result.total : 0
    productPage += 1
    productLoaded = true
  } catch {
    if (generation === productSearchGeneration) productsError.value = true
  } finally {
    if (generation === productSearchGeneration) productsLoading.value = false
  }
}

function openProducts(open: boolean) {
  if (!open) {
    if (productKeyword) resetProductSearch('')
    return
  }
  if (!productLoaded && !productSearchTimer) void loadProducts()
}

function resetProductSearch(keyword: string) {
  productKeyword = keyword
  productSearchGeneration += 1
  productPage = 0
  productsTotal = 0
  productOptions.value = []
  productLoaded = false
  productsLoading.value = false
  productsError.value = false
  clearTimeout(productSearchTimer)
  productSearchTimer = undefined
}

function searchProducts(input: string) {
  const keyword = input.trim()
  if (keyword === productKeyword) return
  resetProductSearch(keyword)
  productSearchTimer = setTimeout(() => {
    productSearchTimer = undefined
    void loadProducts()
  }, 250)
}

function loadMoreProducts(event: UIEvent) {
  const menu = event.target as HTMLElement
  if (!productsError.value && productPage * productPageSize < productsTotal
    && menu.scrollTop + menu.clientHeight >= menu.scrollHeight - 24) {
    void loadProducts()
  }
}

const pageIndex = ref(0)
const pageSize = ref(20)
const pageSizes = ['20', '50', '100']
const page = ref<LatestDevicePage>()
const loading = ref(false)
const error = ref('')
const exporting = ref(false)
let pageGeneration = 0

const rows = computed(() => (page.value?.data ?? []).map(row => ({
  ...row,
  values: new Map(row.properties.map(property => [property.property, property])),
})))
const columns = computed<TableColumnsType<LatestDeviceRow>>(() => [
  { title: '设备', key: 'device', width: 190, fixed: 'left' },
  { title: '最近采集时间', key: 'latestTimestamp', width: 180 },
  ...(page.value?.columns ?? []).map(({ id, name }) => ({
    title: name || id,
    key: `property:${id}`,
    dataIndex: id,
    width: 155,
    ellipsis: { showTitle: false },
  })),
])
const tableWidth = computed(() => 370 + 155 * (page.value?.columns.length ?? 0))
const pagination = computed<TablePaginationConfig>(() => ({
  current: pageIndex.value + 1,
  pageSize: pageSize.value,
  total: page.value?.total ?? 0,
  pageSizeOptions: pageSizes,
  showSizeChanger: true,
  showTotal: total => `共 ${total} 台设备`,
}))

async function loadPage() {
  const generation = ++pageGeneration
  const id = productId.value
  error.value = ''
  loading.value = !!id
  if (!id) return
  try {
    const response = await queryLatestDeviceData(id, pageIndex.value, pageSize.value)
    if (generation !== pageGeneration) return
    if (!response.success || !response.result) throw new Error('读取实时数据失败')
    page.value = response.result
    const lastPageIndex = Math.max(0, Math.ceil(page.value.total / pageSize.value) - 1)
    if (pageIndex.value > lastPageIndex) {
      pageIndex.value = lastPageIndex
      await loadPage()
    }
  } catch (cause) {
    if (generation === pageGeneration) {
      page.value = undefined
      error.value = cause instanceof Error ? cause.message : '读取实时数据失败'
    }
  } finally {
    if (generation === pageGeneration) loading.value = false
  }
}

function changeProduct() {
  pageIndex.value = 0
  page.value = undefined
  void loadPage()
}

function changePage(pagination: TablePaginationConfig) {
  const size = pagination.pageSize ?? 20
  pageIndex.value = size === pageSize.value ? (pagination.current ?? 1) - 1 : 0
  pageSize.value = size
  void loadPage()
}

function formatValue(value: unknown): string {
  if (value == null) return '—'
  return typeof value === 'string' ? value : (JSON.stringify(value) ?? '—')
}

function propertyTitle(property?: LatestPropertyValue): string | undefined {
  if (!property) return undefined
  return `${formatValue(property.value)}\n采集时间 ${formatShanghaiTime(property.timestamp)}`
}

async function exportAll() {
  const id = productId.value
  if (!id || exporting.value) return
  exporting.value = true
  try {
    const data: unknown = await exportLatestDeviceData(id)
    if (!(data instanceof Blob)) throw new Error('导出文件无效')
    const signature = new Uint8Array(await data.slice(0, 4).arrayBuffer())
    if (signature.length !== 4 || signature[0] !== 0x50 || signature[1] !== 0x4b
      || signature[2] !== 0x03 || signature[3] !== 0x04) {
      throw new Error('导出文件无效')
    }
    const url = URL.createObjectURL(data)
    const link = document.createElement('a')
    link.href = url
    link.download = `${id.replace(/[<>:"/\\|?*]/g, '_')}-实时数据.xlsx`
    link.click()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch {
    onlyMessage('导出实时数据失败，请稍后重试', 'error')
  } finally {
    exporting.value = false
  }
}

onBeforeUnmount(() => {
  clearTimeout(productSearchTimer)
  productSearchGeneration += 1
  pageGeneration += 1
})
</script>

<style scoped>
.latest-device-data {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 16px;
  min-width: 0;
}

.toolbar,
.product-filter {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
}

.toolbar {
  justify-content: space-between;
}

.product-filter {
  min-width: 0;
}

.product-filter :deep(.ant-select) {
  width: min(360px, 65vw);
  max-width: 100%;
}

small {
  color: var(--app-text-secondary);
}

.property-value {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
