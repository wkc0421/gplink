<template>
  <j-page-container>
    <div class="history-page">
      <div class="heading"><div><h2>历史统计</h2><p>统一管理累计采集项的产品默认配置与设备例外。</p></div><a-button :loading="loading" @click="load">刷新</a-button></div>
      <a-alert type="info" show-icon message="适用于累计电量等数值型累计采集项。新增后从北京时间今天零点开始统计；历史数据可逐项回填。移除统计仅停止后续统计，已有数据保留。" />
      <a-tabs v-model:active-key="scope" @change="scopeChanged"><a-tab-pane key="PRODUCT" tab="产品默认" /><a-tab-pane key="DEVICE" tab="设备实际生效" /></a-tabs>
      <div class="filters">
        <TargetSelect v-model:value="productIds" kind="PRODUCT" label="筛选产品" :disabled="saving" @update:value="productFilterChanged" />
        <TargetSelect v-if="scope === 'DEVICE'" v-model:value="deviceIds" kind="DEVICE" :product-ids="productIds" label="筛选设备" :disabled="saving" @update:value="search" />
        <a-input-search v-model:value="keyword" aria-label="搜索采集项名称或 ID" placeholder="搜索采集项名称或 ID" allow-clear @search="search" />
        <a-select v-model:value="state" aria-label="统计状态" :options="stateOptions" @change="search" />
        <a-select v-if="scope === 'DEVICE'" v-model:value="source" aria-label="统计来源" :options="sourceOptions" @change="search" />
      </div>
      <div class="toolbar">
        <a-space wrap><a-button v-if="scope === 'DEVICE'" @click="showExceptions">仅看设备例外</a-button><span class="secondary">{{ scope === 'PRODUCT' ? '产品默认影响继承它的设备，已有设备例外保持不变。' : '设备单独设置优先于产品默认，可随时恢复继承。' }}</span></a-space>
        <a-button v-if="canUpdate" type="primary" :disabled="saving" @click="openAdd">{{ scope === 'PRODUCT' ? '批量新增产品默认' : '批量新增设备例外' }}</a-button>
      </div>
      <div v-if="selected.size" class="selection-bar">
        <span>已选择 {{ selected.size }} 项</span><a-button danger :disabled="!canUpdate || saving" @click="prepareChange('DISABLE', [...selected.values()])">批量移除统计</a-button>
        <a-button v-if="scope === 'DEVICE'" :disabled="!canUpdate || saving || !selectedOverrides.length" @click="prepareChange('INHERIT', selectedOverrides)">批量恢复继承</a-button><a-button type="link" @click="selected = new Map()">取消选择</a-button>
      </div>
      <a-alert v-if="error" type="error" show-icon :message="error" class="notice"><template #action><a-button size="small" @click="load">重试</a-button></template></a-alert>
      <a-alert v-if="summary" :type="summaryFailed ? 'warning' : 'success'" show-icon :message="summary" closable class="notice" @close="summary = ''" />
      <a-table class="history-table" :columns="columns" :data-source="rows" :row-key="historyRowKey" :loading="loading" :scroll="{ x: 1600 }" size="middle" :pagination="pagination" :row-selection="canUpdate ? selection : undefined" @change="pageChanged">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'target'"><div>{{ scope === 'DEVICE' ? record.deviceName || record.deviceId : record.productName || record.productId }}</div><small>{{ scope === 'DEVICE' ? record.deviceId : record.productId }}</small><div v-if="scope === 'DEVICE'" class="secondary">{{ record.productName || record.productId }}</div></template>
          <template v-else-if="column.key === 'property'"><div>{{ record.propertyName || record.propertyId }}</div><small>{{ record.propertyId }} · {{ record.valueType || '—' }}<template v-if="record.unit"> · {{ record.unit }}</template></small><div v-if="record.propertySource === 'DEVICE'"><a-tag>设备自定义</a-tag></div><a-tag v-if="!record.eligible" color="warning">采集项已失效</a-tag></template>
          <template v-else-if="column.key === 'enabled'"><a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag><div v-if="scope === 'DEVICE'" class="secondary">{{ historyModeLabel(record.mode) }}</div></template>
          <template v-else-if="column.key === 'start'">{{ formatShanghaiTime(record.config?.startTime) }}</template>
          <template v-else-if="column.key === 'coverage'"><template v-if="record.config?.coverageFrom != null">{{ formatShanghaiTime(record.config.coverageFrom) }}<br />至 {{ formatShanghaiTime(record.config.coverageTo) }}</template><span v-else>—</span></template>
          <template v-else-if="column.key === 'status'"><div>{{ historyStatusLabel(record.config?.status) }}</div><a-tooltip v-if="record.config?.lastError" :title="record.config.lastError"><div class="error-text">{{ record.config.lastError }}</div></a-tooltip></template>
          <template v-else-if="column.key === 'actions'"><div class="row-actions">
            <a-button v-if="canUpdate && record.enabled" type="link" danger @click="prepareChange('DISABLE', [record as HistoryManagementRow])">移除统计</a-button>
            <a-button v-if="canUpdate && !record.enabled && record.eligible" type="link" @click="prepareChange('ENABLE', [record as HistoryManagementRow])">启用统计</a-button>
            <a-button v-if="canUpdate && scope === 'DEVICE' && record.mode !== 'INHERIT'" type="link" @click="prepareChange('INHERIT', [record as HistoryManagementRow])">恢复继承</a-button>
            <a-button v-if="canUpdate" type="link" :disabled="!record.enabled || !record.eligible" @click="taskDialogs?.rebuild(record as HistoryManagementRow)">回填 / 重算</a-button>
            <a-button v-if="record.config?.taskId" type="link" @click="taskDialogs?.showTask(record as HistoryManagementRow)">查看任务</a-button>
          </div></template>
        </template>
        <template #emptyText>{{ error ? '加载失败，请重试' : '当前筛选下没有统计采集项' }}</template>
      </a-table>
    </div>

    <a-drawer v-model:open="addOpen" root-class-name="history-add-drawer" role="dialog" aria-modal="true" :aria-label="scope === 'PRODUCT' ? '批量新增产品默认' : '批量新增设备例外'" :title="scope === 'PRODUCT' ? '批量新增产品默认' : '批量新增设备例外'" width="min(100vw, 1050px)" :mask-closable="!saving" :closable="!saving" @close="closeAdd">
      <a-alert type="info" show-icon :message="scope === 'PRODUCT' ? '选择产品，再勾选各产品需要统计的累计采集项。' : '选择设备，再勾选数值型累计采集项，包括设备自定义项。继承启用的项目也可设为设备例外，之后产品停用时仍保持启用。'" />
      <div class="add-filters">
        <TargetSelect v-model:value="addProductIds" kind="PRODUCT" :label="scope === 'PRODUCT' ? '选择产品' : '按产品筛选设备'" :disabled="saving" @update:value="addProductsChanged" />
        <TargetSelect v-if="scope === 'DEVICE'" v-model:value="addDeviceIds" kind="DEVICE" :product-ids="addProductIds" label="选择设备" :disabled="saving" @update:value="addTargetsChanged" />
        <a-input-search v-model:value="addKeyword" aria-label="搜索待新增采集项" placeholder="搜索采集项名称或 ID" :disabled="saving" allow-clear @search="candidateSearch" />
      </div>
      <div class="selection-bar"><span>已选择 {{ candidatesSelected.size }} / 500 项</span><a-button type="link" :disabled="saving" @click="candidatesSelected = new Map()">取消选择</a-button></div>
      <a-alert v-if="addError" type="error" show-icon :message="addError" class="notice"><template #action><a-button size="small" @click="loadCandidates">重试</a-button></template></a-alert>
      <a-alert v-if="batchErrors.length" type="warning" show-icon message="部分项目未保存，可核对后重试。" class="notice"><template #description><div v-for="item in batchErrors" :key="item.key">{{ item.label }}：{{ item.message }}</div></template></a-alert>
      <a-table class="candidate-table" :columns="candidateColumns" :data-source="candidates" :row-key="historyRowKey" :loading="candidateLoading" :pagination="candidatePagination" :row-selection="candidateSelection" size="small" :scroll="{ x: 750 }" @change="candidatePageChanged">
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'target'"><div>{{ historyTargetName(record as HistoryManagementRow) }}</div><small>{{ record.deviceId || record.productId }}</small><div v-if="scope === 'DEVICE'" class="secondary">{{ record.productName }}</div></template>
          <template v-else-if="column.key === 'property'"><div>{{ record.propertyName }}</div><small>{{ record.propertyId }} · {{ record.valueType }} {{ record.unit || '' }}</small><a-tag v-if="record.propertySource === 'DEVICE'">设备自定义</a-tag></template>
          <template v-else-if="column.key === 'enabled'"><a-tag v-if="explicitlyEnabled(record as HistoryManagementRow)" color="green">已明确启用</a-tag><span v-else-if="record.enabled">继承启用，可设为例外</span><span v-else>{{ scope === 'DEVICE' && record.mode !== 'INHERIT' ? '设备单独停用' : '未启用' }}</span></template>
        </template>
        <template #emptyText>{{ hasAddTargets ? '暂无可选数值型采集项' : scope === 'PRODUCT' ? '请先选择产品' : '请先选择设备' }}</template>
      </a-table>
      <template #footer><div class="drawer-footer"><span>仅启用所选对象的采集项，不修改物模型。</span><a-space><a-button :disabled="saving" @click="closeAdd">关闭</a-button><a-button type="primary" :loading="saving" :disabled="!candidatesSelected.size || !canUpdate" @click="submitAdd">启用所选 {{ candidatesSelected.size }} 项</a-button></a-space></div></template>
    </a-drawer>

    <a-modal v-model:open="confirmOpen" :title="changeMode === 'INHERIT' ? '恢复产品默认' : changeMode === 'DISABLE' ? '移除历史统计' : '启用历史统计'" :confirm-loading="saving" :mask-closable="false" :cancel-button-props="{ disabled: saving }" @ok="submitChange">
      <p>本次处理 {{ changeRows.length }} 项。</p><p>{{ confirmMessage }}</p>
      <p v-if="changeMode === 'INHERIT'">按当前产品默认，预计启用 {{ changeRows.filter(row => row.productEnabled && row.eligible).length }} 项、停用 {{ changeRows.filter(row => !row.productEnabled || !row.eligible).length }} 项。产品未启用、采集项失效或设备自定义属性恢复后停止统计。</p>
      <a-alert v-if="changeError" type="error" :message="changeError" />
      <div v-if="batchErrors.length" class="batch-errors"><p v-for="item in batchErrors" :key="item.key">{{ item.label }}：{{ item.message }}</p></div>
    </a-modal>
    <TaskDialogs ref="taskDialogs" :can-update="canUpdate" @changed="load" />
  </j-page-container>
</template>

<script setup lang="ts" name="HistoryStatisticsManagement">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import { useAuthStore } from '@/store'
import { batchHistoryManagement, queryHistoryCandidates, queryHistoryManagement } from '../../../api/historyStatistics'
import type { HistoryBatchResult, HistoryManagementRow, HistoryMode, HistoryQuery, HistoryScope } from '../../../api/historyStatistics'
import { formatShanghaiTime } from '../Product/Detail/HistoryStatistics/utils'
import { batchItems, errorText, historyModeLabel, historyRowKey, historyStatusLabel, historyTargetName, resultValue, selectionChanged } from './utils'
import TargetSelect from './TargetSelect.vue'
import TaskDialogs from './TaskDialogs.vue'

const auth = useAuthStore()
const scope = ref<HistoryScope>('PRODUCT')
const canUpdate = computed(() => auth.hasPermission(`device/HistoryStatistics:${scope.value === 'PRODUCT' ? 'updateProduct' : 'updateDevice'}`))
const productIds = ref<string[]>([]), deviceIds = ref<string[]>([]), keyword = ref(''), state = ref('enabled'), source = ref('all')
const pageIndex = ref(0), pageSize = ref(20), total = ref(0), rows = ref<HistoryManagementRow[]>([])
const loading = ref(false), saving = ref(false), error = ref(''), summary = ref(''), summaryFailed = ref(false)
const selected = ref(new Map<string, HistoryManagementRow>()), taskDialogs = ref<InstanceType<typeof TaskDialogs>>()
let listGeneration = 0, candidateGeneration = 0
const stateOptions = [{ value: 'enabled', label: '已启用' }, { value: 'all', label: '全部状态' }, { value: 'disabled', label: '已停用' }]
const sourceOptions = [{ value: 'all', label: '全部来源' }, { value: 'INHERIT', label: '继承产品' }, { value: 'OVERRIDE', label: '设备例外' }, { value: 'ENABLE', label: '设备单独启用' }, { value: 'DISABLE', label: '设备单独停用' }]
const columns = computed(() => [{ title: scope.value === 'PRODUCT' ? '产品' : '设备 / 所属产品', key: 'target', width: 230 }, { title: '采集项', key: 'property', width: 210 }, { title: '状态 / 来源', key: 'enabled', width: 150 }, { title: '统计起点', key: 'start', width: 180 }, { title: '已覆盖区间', key: 'coverage', width: 210 }, { title: '执行状态', key: 'status', width: 150 }, { title: '操作', key: 'actions', width: 300, fixed: 'right' as const }])
const candidateColumns = [{ title: '对象', key: 'target', width: 250 }, { title: '采集项', key: 'property', width: 300 }, { title: '当前状态', key: 'enabled', width: 130 }]
const pagination = computed(() => ({ current: pageIndex.value + 1, pageSize: pageSize.value, total: total.value, showSizeChanger: true, showTotal: (n: number) => `共 ${n} 项` }))
const selection = computed(() => ({ selectedRowKeys: [...selected.value.keys()], preserveSelectedRowKeys: true, onChange: (keys: (string | number)[]) => { selected.value = selectionChanged(selected.value, keys, rows.value) }, getCheckboxProps: (row: HistoryManagementRow) => ({ disabled: saving.value || loading.value || (selected.value.size >= 500 && !selected.value.has(historyRowKey(row))) }) }))
const selectedOverrides = computed(() => [...selected.value.values()].filter(row => row.mode !== 'INHERIT'))
function query(): HistoryQuery { return { scope: scope.value, pageIndex: pageIndex.value, pageSize: pageSize.value, productIds: productIds.value, deviceIds: scope.value === 'DEVICE' ? deviceIds.value : undefined, keyword: keyword.value.trim() || undefined, enabled: state.value === 'all' ? undefined : state.value === 'enabled', source: scope.value === 'DEVICE' && source.value !== 'all' ? source.value as HistoryQuery['source'] : undefined } }
async function load() {
  const expected = ++listGeneration; loading.value = true; error.value = ''
  try { const result = resultValue(await queryHistoryManagement(query()), '读取配置失败'); if (expected !== listGeneration) return; rows.value = result.data; total.value = result.total }
  catch (e) { if (expected === listGeneration) { error.value = errorText(e); rows.value = []; total.value = 0 } }
  finally { if (expected === listGeneration) loading.value = false }
}
function search() { pageIndex.value = 0; selected.value = new Map(); load() }
function productFilterChanged() { deviceIds.value = []; search() }
function scopeChanged() { rows.value = []; total.value = 0; selected.value = new Map(); deviceIds.value = []; source.value = 'all'; state.value = 'enabled'; taskDialogs.value?.close(); search() }
function showExceptions() { source.value = 'OVERRIDE'; state.value = 'all'; search() }
function pageChanged(p: TablePaginationConfig) { pageIndex.value = (p.current || 1) - 1; pageSize.value = p.pageSize || 20; load() }

const addOpen = ref(false), addProductIds = ref<string[]>([]), addDeviceIds = ref<string[]>([]), addKeyword = ref('')
const candidates = ref<HistoryManagementRow[]>([]), candidateLoading = ref(false), addError = ref(''), candidatesSelected = ref(new Map<string, HistoryManagementRow>())
const candidatePage = ref(0), candidateSize = ref(50), candidateTotal = ref(0)
const batchErrors = ref<Array<{ key: string; label: string; message: string }>>([])
const hasAddTargets = computed(() => (scope.value === 'PRODUCT' ? addProductIds.value : addDeviceIds.value).length > 0)
const candidatePagination = computed(() => ({ current: candidatePage.value + 1, pageSize: candidateSize.value, total: candidateTotal.value, showSizeChanger: true, pageSizeOptions: ['20', '50', '100', '200'], showTotal: (n: number) => `共 ${n} 项` }))
const explicitlyEnabled = (row: HistoryManagementRow) => row.enabled && (row.scope === 'PRODUCT' || row.mode === 'ENABLE')
const candidateSelection = computed(() => ({ selectedRowKeys: [...candidatesSelected.value.keys()], preserveSelectedRowKeys: true, onChange: (keys: (string | number)[]) => { candidatesSelected.value = selectionChanged(candidatesSelected.value, keys, candidates.value) }, getCheckboxProps: (row: HistoryManagementRow) => ({ disabled: saving.value || candidateLoading.value || explicitlyEnabled(row) || !row.eligible || (candidatesSelected.value.size >= 500 && !candidatesSelected.value.has(historyRowKey(row))) }) }))
function openAdd() { addProductIds.value = [...productIds.value]; addDeviceIds.value = [...deviceIds.value]; addKeyword.value = ''; candidatesSelected.value = new Map(); batchErrors.value = []; addError.value = ''; addOpen.value = true; candidateSearch() }
function closeAdd() { if (saving.value) return; addOpen.value = false; candidateGeneration++; candidatesSelected.value = new Map() }
function addProductsChanged() { if (scope.value === 'DEVICE') addDeviceIds.value = []; addTargetsChanged() }
function addTargetsChanged() { const ids = new Set(scope.value === 'PRODUCT' ? addProductIds.value : addDeviceIds.value); candidatesSelected.value = new Map([...candidatesSelected.value].filter(([, row]) => ids.has(scope.value === 'PRODUCT' ? row.productId : row.deviceId!))); candidateSearch() }
function candidateSearch() { candidatePage.value = 0; loadCandidates() }
function candidatePageChanged(p: TablePaginationConfig) { candidatePage.value = (p.current || 1) - 1; candidateSize.value = p.pageSize || 50; loadCandidates() }
async function loadCandidates() {
  const expected = ++candidateGeneration; addError.value = ''
  if (!hasAddTargets.value) { candidates.value = []; candidateTotal.value = 0; candidateLoading.value = false; return }
  candidateLoading.value = true
  try { const result = resultValue(await queryHistoryCandidates({ scope: scope.value, productIds: addProductIds.value, deviceIds: addDeviceIds.value, keyword: addKeyword.value.trim() || undefined, pageIndex: candidatePage.value, pageSize: candidateSize.value }), '读取可选采集项失败'); if (expected !== candidateGeneration) return; candidates.value = result.data; candidateTotal.value = result.total }
  catch (e) { if (expected === candidateGeneration) { addError.value = errorText(e); candidates.value = []; candidateTotal.value = 0 } }
  finally { if (expected === candidateGeneration) candidateLoading.value = false }
}
function applyResult(result: HistoryBatchResult, submitted: HistoryManagementRow[]) {
  const byKey = new Map(submitted.map(row => [historyRowKey(row), row]))
  batchErrors.value = result.items.filter(item => !item.success).map(item => { const key = historyRowKey(item); const row = byKey.get(key); return { key, label: row ? `${historyTargetName(row)} / ${row.propertyName}（${row.propertyId}）` : item.propertyId, message: item.message || '保存失败' } })
  const skipped = result.items.filter(item => item.success && item.changed === false).length
  summary.value = `处理 ${result.total} 项：成功 ${result.succeeded} 项${skipped ? `（其中 ${skipped} 项已生效，无需重复修改）` : ''}，失败 ${result.failed} 项。`
  summaryFailed.value = result.failed > 0
  return new Set(batchErrors.value.map(item => item.key))
}
async function submitAdd() {
  if (!canUpdate.value || saving.value || !candidatesSelected.value.size) return
  saving.value = true; addError.value = ''
  const submitted = [...candidatesSelected.value.values()]
  try { const result = resultValue(await batchHistoryManagement(batchItems(submitted, 'ENABLE')), '启用失败'); const failures = applyResult(result, submitted); candidatesSelected.value = new Map(submitted.filter(row => failures.has(historyRowKey(row))).map(row => [historyRowKey(row), row])); await Promise.all([load(), loadCandidates()]); if (!result.failed) addOpen.value = false }
  catch (e) { addError.value = errorText(e) }
  finally { saving.value = false }
}

const confirmOpen = ref(false), changeMode = ref<HistoryMode>('DISABLE'), changeRows = ref<HistoryManagementRow[]>([]), changeError = ref('')
const confirmMessage = computed(() => changeMode.value === 'INHERIT' ? '清除所选设备例外，后续跟随产品默认。已有历史数据保留。' : changeMode.value === 'DISABLE' ? scope.value === 'PRODUCT' ? '停用所选产品默认，仅影响继承该默认的设备；设备例外保持不变。已有历史数据保留。' : '为所选设备设置停用例外，后续产品默认开启时仍保持停用。已有历史数据保留。' : '启用所选采集项，历史范围可另外逐项回填。')
function prepareChange(mode: HistoryMode, items: HistoryManagementRow[]) { if (!canUpdate.value) return; changeMode.value = mode; changeRows.value = items; changeError.value = ''; batchErrors.value = []; confirmOpen.value = true }
async function submitChange() {
  if (!canUpdate.value || saving.value || !changeRows.value.length) return
  saving.value = true; changeError.value = ''
  const submitted = changeRows.value
  try { const result = resultValue(await batchHistoryManagement(batchItems(submitted, changeMode.value)), '保存失败'); const failures = applyResult(result, submitted); changeRows.value = submitted.filter(row => failures.has(historyRowKey(row))); selected.value = new Map([...selected.value].filter(([key]) => failures.has(key))); await load(); if (!result.failed) confirmOpen.value = false }
  catch (e) { changeError.value = errorText(e) }
  finally { saving.value = false }
}
watch(canUpdate, value => { if (!value) { selected.value = new Map(); candidatesSelected.value = new Map(); addOpen.value = false; confirmOpen.value = false; taskDialogs.value?.close() } })
onBeforeUnmount(() => { listGeneration++; candidateGeneration++ })
load()
</script>

<style scoped>
.history-page { padding:20px;background:var(--app-surface,#fff);border-radius:8px; }
.heading,.toolbar,.selection-bar,.drawer-footer { display:flex;align-items:center;justify-content:space-between;gap:12px;flex-wrap:wrap; }
.heading h2 { margin:0 0 4px;font-size:22px; }.heading p,.secondary,small,.drawer-footer>span { color:var(--app-text-secondary,#777); }.heading { margin-bottom:16px; }
.filters { display:grid;align-items:end;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:0 0 16px; }.toolbar { margin-bottom:16px; }.selection-bar { justify-content:flex-start;padding:10px 12px;margin:12px 0;background:var(--app-bg,#f5f7fa);border-radius:6px; }.notice { margin:12px 0; }.row-actions { display:flex;flex-wrap:wrap;gap:0 2px; }.row-actions .ant-btn { padding:0 4px; }.error-text { max-width:150px;color:#cf1322;overflow:hidden;text-overflow:ellipsis;white-space:nowrap; }.add-filters { display:grid;grid-template-columns:1fr;gap:12px;margin:16px 0; }.batch-errors { max-height:240px;overflow:auto;color:#cf1322; }.history-table small,.candidate-table small { display:block; }
@media(max-width:700px) { .history-page { padding:12px; }.filters { grid-template-columns:1fr; } }
</style>
