<template>
  <a-modal open title="导入采集项到项目库" :width="1100" :mask-closable="false" :footer="null" @cancel="close">
    <a-alert type="info" show-icon :message="`目标项目库：${library.name}`" description="先预览并修正名称、ID 和分类，再确认选中的有效行。同 ID 的现有采集项会跳过，不会被覆盖。" />
    <div class="import-toolbar">
      <a-upload accept=".xlsx,.xls,.csv" :show-upload-list="false" :before-upload="preview">
        <a-button :loading="loading" :disabled="saving">选择 Excel / CSV 文件</a-button>
      </a-upload>
      <a-button type="link" @click="downloadTemplate">下载模板</a-button>
      <span class="muted">{{ filename || '支持采集项 ID、名称和分类列' }}</span>
    </div>
    <a-alert v-if="error" type="error" show-icon :message="error" class="notice" />
    <a-alert v-for="(warning, index) in warnings" :key="index" type="warning" show-icon :message="warning" class="notice" />
    <template v-if="rows.length">
      <div class="preview-summary">
        <span>共 {{ rows.length }} 行 · 有效 {{ validRows.length }} 行 · 已选 {{ selectedKeys.length }} 行</span>
        <a-button type="link" @click="selectValid">选择全部有效行</a-button>
      </div>
      <a-table :columns="columns" :data-source="rows" row-key="previewKey" size="small" :loading="loading"
        :scroll="{ x: 920, y: 390 }" :pagination="{ pageSize: 20, showSizeChanger: true }"
        :row-selection="{ selectedRowKeys: selectedKeys, preserveSelectedRowKeys: true, onChange: selectRows, getCheckboxProps: (row: PreviewRow) => ({ disabled: rowErrors(row).length > 0 || saving }) }">
        <template #bodyCell="{ column, record }">
          <a-input v-if="column.key === 'code'" v-model:value="record.code" :maxlength="64" aria-label="预览采集项 ID" :disabled="saving" @change="edited(record)" />
          <a-input v-else-if="column.key === 'name'" v-model:value="record.name" :maxlength="64" aria-label="预览采集项名称" :disabled="saving" @change="edited(record)" />
          <a-input v-else-if="column.key === 'categoryPath'" v-model:value="record.categoryPath" placeholder="如 电气 / 电压" aria-label="预览分类" :disabled="saving" @change="edited(record)" />
          <template v-else-if="column.key === 'errors'">
            <span v-if="rowErrors(record).length" class="row-error">{{ rowErrors(record).join('；') }}</span>
            <a-tag v-else color="green">可导入</a-tag>
          </template>
        </template>
      </a-table>
    </template>
    <a-empty v-else-if="!loading && !result" description="选择文件后，在这里检查导入内容" />
    <a-alert v-if="result" class="notice" type="success" show-icon :message="`已新增 ${result.created} 项，跳过 ${result.skipped} 项`" />
    <div v-if="result?.conflicts?.length" class="conflicts">
      <strong>以下 ID 已存在，已保留库中的内容</strong>
      <div v-for="(conflict, index) in result.conflicts" :key="index">{{ conflict.code }} · {{ conflict.existingName || conflict.message || conflict.name }}</div>
    </div>
    <div class="dialog-footer">
      <a-button :disabled="saving" @click="close">关闭</a-button>
      <j-permission-button type="primary" hasPermission="device/PropertyLibrary:import" :loading="saving" :disabled="!selectedKeys.length || loading" @click="confirm">
        确认导入 {{ selectedKeys.length ? `（${selectedKeys.length}）` : '' }}
      </j-permission-button>
    </div>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { previewPropertyLibraryImport, confirmPropertyLibraryImport } from '../../../api/propertyLibrary'
import type { PropertyLibrary, PropertyLibraryImportRow, PropertyLibraryImportResult } from '../../../api/propertyLibrary'
import { errorMessage, previewRowErrors, readResult } from './utils'

interface PreviewRow extends PropertyLibraryImportRow { previewKey: string; changed?: boolean }
const props = defineProps<{ library: PropertyLibrary }>()
const emit = defineEmits<{ close: []; imported: [] }>()
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const filename = ref('')
const rows = ref<PreviewRow[]>([])
const warnings = ref<string[]>([])
const selectedKeys = ref<string[]>([])
const result = ref<PropertyLibraryImportResult>()
const columns = [
  { title: '源行号', dataIndex: 'rowNumber', key: 'rowNumber', width: 80 },
  { title: '采集项 ID', key: 'code', width: 180 },
  { title: '采集项名称', key: 'name', width: 220 },
  { title: '分类路径', key: 'categoryPath', width: 220 },
  { title: '检查结果', key: 'errors', width: 230 },
]
const rowErrors = (row: PreviewRow) => previewRowErrors(row, rows.value)
const downloadTemplate = () => {
  const blob = new Blob(['\uFEFF采集项ID,采集项名称,分类路径,备注\r\nUa,A相电压,电参量/电压,\r\n'], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = '采集项库导入模板.csv'
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
const validRows = computed(() => rows.value.filter(row => !rowErrors(row).length))
const selectValid = () => { selectedKeys.value = validRows.value.map(row => row.previewKey) }
const selectRows = (keys: (string | number)[]) => { selectedKeys.value = keys.map(String) }
const edited = (row: PreviewRow) => {
  row.changed = true
  selectedKeys.value = selectedKeys.value.filter(key => {
    const item = rows.value.find(value => value.previewKey === key)
    return item && !rowErrors(item).length
  })
}
const preview = async (file: File) => {
  if (saving.value || loading.value) return false
  error.value = ''
  result.value = undefined
  selectedKeys.value = []
  rows.value = []
  warnings.value = []
  filename.value = file.name
  loading.value = true
  try {
    const data = readResult(await previewPropertyLibraryImport(props.library.id, file))
    rows.value = (data.rows || []).map((row, index) => ({ ...row, previewKey: `row-${index}` }))
    warnings.value = data.warnings || []
    selectValid()
  } catch (e) { error.value = errorMessage(e, '文件预览失败，请检查文件后重试') }
  finally { loading.value = false }
  return false
}
const confirm = async () => {
  if (saving.value || !selectedKeys.value.length) return
  const selected = new Set(selectedKeys.value)
  const chosen = rows.value.filter(row => selected.has(row.previewKey))
  if (chosen.some(row => rowErrors(row).length)) { error.value = '请修正选中行的错误后重试'; return }
  saving.value = true
  error.value = ''
  try {
    const payload = chosen.map(({ previewKey, changed, ...row }) => ({ ...row, code: row.code.trim(), name: row.name.trim(), categoryPath: row.categoryPath?.trim(), errors: [] }))
    result.value = readResult(await confirmPropertyLibraryImport(props.library.id, payload))
    selectedKeys.value = []
    rows.value = rows.value.filter(row => !selected.has(row.previewKey))
    emit('imported')
  } catch (e) { error.value = errorMessage(e, '导入失败，已保留预览内容，请修正后重试') }
  finally { saving.value = false }
}
const close = () => { if (!saving.value && !loading.value) emit('close') }
</script>

<style scoped>
.import-toolbar, .preview-summary, .dialog-footer { display: flex; align-items: center; gap: 12px; margin: 16px 0; }
.preview-summary { justify-content: space-between; }
.dialog-footer { justify-content: flex-end; margin-bottom: 0; }
.muted { color: var(--app-text-secondary); }
.notice { margin: 12px 0; }
.row-error { color: var(--ant-color-error, #d4380d); }
.conflicts { padding: 12px; background: var(--app-surface); max-height: 140px; overflow: auto; }
</style>
