<template>
  <j-page-container>
    <div class="property-library-page">
      <div class="page-heading">
        <div><h2>采集项库</h2><p>按项目整理采集项名称与 ID，在配置物模型时快速选择。</p></div>
        <a-button :loading="libraryLoading" @click="refresh">刷新</a-button>
      </div>
      <a-alert v-if="libraryError" type="error" show-icon :message="libraryError" class="notice"><template #action><a-button size="small" @click="loadLibraries()">重试</a-button></template></a-alert>
      <div class="library-layout">
        <aside class="library-sidebar">
          <div class="sidebar-heading"><strong>项目库</strong><j-permission-button type="link" hasPermission="device/PropertyLibrary:add" @click="openLibrary()">新建库</j-permission-button></div>
          <a-spin :spinning="libraryLoading">
            <div class="library-list">
              <button v-for="library in libraries" :key="library.id" class="library-option" :class="{ active: library.id === libraryId }" @click="selectLibrary(library.id)">
                <span>{{ library.name }}</span><a-tag v-if="library.id === 'standard'" color="blue">标准</a-tag><a-tag v-else-if="!library.enabled">停用</a-tag>
              </button>
              <a-empty v-if="!libraryLoading && !libraries.length" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无项目库，请先新建" />
            </div>
          </a-spin>
          <template v-if="currentLibrary">
            <div class="sidebar-heading category-heading"><strong>分类</strong><j-permission-button type="link" hasPermission="device/PropertyLibrary:add" @click="openCategory()">新增</j-permission-button></div>
            <a-alert v-if="categoryError" type="error" show-icon :message="categoryError"><template #action><a @click="loadCategories()">重试</a></template></a-alert>
            <a-spin :spinning="categoryLoading">
              <button class="category-all" :class="{ active: !categoryId }" @click="selectCategory()">全部分类</button>
              <button class="category-all" :class="{ active: categoryId === '__uncategorized__' }" @click="selectCategory('__uncategorized__')">未设置分类</button>
              <a-tree v-if="categories.length" :tree-data="categories" :field-names="{ title: 'name', key: 'id', children: 'children' }" :selected-keys="categoryId ? [categoryId] : []" default-expand-all block-node @select="onCategorySelect" />
              <a-empty v-else-if="!categoryLoading" :image="Empty.PRESENTED_IMAGE_SIMPLE" description="暂无分类" />
            </a-spin>
            <div v-if="currentCategory" class="category-actions">
              <j-permission-button size="small" hasPermission="device/PropertyLibrary:update" @click="openCategory(currentCategory)">编辑分类</j-permission-button>
              <j-permission-button size="small" danger hasPermission="device/PropertyLibrary:delete" :pop-confirm="{ title: '删除此分类？含子分类或采集项时需先处理内容。', onConfirm: () => removeCategory(currentCategory!) }">删除</j-permission-button>
            </div>
          </template>
        </aside>

        <section v-if="currentLibrary" class="library-content">
          <div class="library-title">
            <div><h3>{{ currentLibrary.name }}</h3><p>{{ currentLibrary.description || '选择分类或搜索采集项，维护可复用的名称和 ID。' }}</p></div>
            <a-space>
              <j-permission-button hasPermission="device/PropertyLibrary:update" @click="openLibrary(currentLibrary)">编辑库</j-permission-button>
              <j-permission-button v-if="currentLibrary.id !== 'standard'" danger hasPermission="device/PropertyLibrary:delete" :pop-confirm="{ title: `删除项目库“${currentLibrary.name}”？请先移除库中的分类和采集项。`, onConfirm: () => removeLibrary(currentLibrary!) }">删除库</j-permission-button>
            </a-space>
          </div>
          <a-alert v-if="!currentLibrary.enabled" type="warning" show-icon message="此项目库已停用，不会在物模型选择器中显示；已有物模型不受影响。" class="notice" />
          <div class="item-toolbar">
            <a-space wrap>
              <a-input-search v-model:value="keyword" placeholder="搜索采集项名称或 ID" aria-label="搜索采集项" allow-clear style="width: 280px" @search="searchItems" />
              <a-select v-model:value="enabledFilter" aria-label="采集项状态" placeholder="全部状态" style="width: 120px" :options="statusOptions" @change="searchItems" />
              <a-checkbox v-model:checked="includeChildren" @change="searchItems">包含子分类</a-checkbox>
            </a-space>
            <a-space wrap>
              <j-permission-button hasPermission="device/PropertyLibrary:import" @click="importVisible = true">导入 Excel</j-permission-button>
              <j-permission-button type="primary" hasPermission="device/PropertyLibrary:add" @click="openItem()">新增采集项</j-permission-button>
            </a-space>
          </div>
          <div v-if="selectedKeys.length" class="selection-bar">
            <span>已选择 {{ selectedKeys.length }} 项</span>
            <j-permission-button size="small" hasPermission="device/PropertyLibrary:update" @click="openBatch('category')">批量分类</j-permission-button>
            <j-permission-button v-if="libraryId !== 'standard'" size="small" hasPermission="device/PropertyLibrary:update" @click="openBatch('adopt')">采纳到标准库</j-permission-button>
            <a-button size="small" type="link" @click="selectedKeys = []">取消选择</a-button>
          </div>
          <a-alert v-if="itemError" type="error" show-icon :message="itemError" class="notice"><template #action><a-button size="small" @click="loadItems">重试</a-button></template></a-alert>
          <a-table :columns="columns" :data-source="items" :loading="itemLoading" row-key="id" size="middle" :scroll="{ x: 860 }"
            :pagination="{ current: pageIndex + 1, pageSize, total, showSizeChanger: true, showTotal: (value: number) => `共 ${value} 项` }"
            :row-selection="{ selectedRowKeys: selectedKeys, preserveSelectedRowKeys: true, onChange: (keys: (string | number)[]) => selectedKeys = keys.map(String) }" @change="tableChange">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'code'"><span class="item-code">{{ record.code }}</span></template>
              <template v-else-if="column.key === 'categoryId'">{{ categoryLabel(record.categoryId) }}</template>
              <template v-else-if="column.key === 'enabled'"><a-tag :color="record.enabled ? 'green' : 'default'">{{ record.enabled ? '启用' : '停用' }}</a-tag></template>
              <template v-else-if="column.key === 'action'">
                <a-space :size="4">
                  <j-permission-button type="link" hasPermission="device/PropertyLibrary:update" @click="openItem(record)">编辑</j-permission-button>
                  <j-permission-button type="link" hasPermission="device/PropertyLibrary:update" @click="toggleItem(record)">{{ record.enabled ? '停用' : '启用' }}</j-permission-button>
                  <j-permission-button type="link" danger hasPermission="device/PropertyLibrary:delete" :pop-confirm="{ title: `删除“${record.name}（${record.code}）”？已有物模型保持不变。`, onConfirm: () => removeItem(record) }">删除</j-permission-button>
                </a-space>
              </template>
            </template>
            <template #emptyText><a-empty description="暂无采集项，可新建或导入 Excel" /></template>
          </a-table>
        </section>
        <section v-else class="library-content empty-content"><a-empty description="选择一个项目库，开始管理采集项" /></section>
      </div>
    </div>

    <a-modal v-model:open="libraryDialog" :title="libraryForm.id ? '编辑项目库' : '新建项目库'" :confirm-loading="saving" :mask-closable="false" @ok="saveLibrary" @cancel="dialogError = ''">
      <a-alert v-if="dialogError" type="error" show-icon :message="dialogError" class="notice" />
      <a-form name="property-library" ref="libraryFormRef" :model="libraryForm" layout="vertical">
        <a-form-item label="项目库名称" name="name" :rules="[{ required: true, whitespace: true, message: '请填写项目库名称' }, { max: 64 }]" ><a-input v-model:value="libraryForm.name" :maxlength="64" /></a-form-item>
        <a-form-item label="项目库编码" name="code" :rules="libraryCodeRules"><a-input v-model:value="libraryForm.code" :disabled="!!libraryForm.id" :maxlength="64" placeholder="例如 project_a" /></a-form-item>
        <a-form-item label="说明"><a-textarea v-model:value="libraryForm.description" :maxlength="500" :rows="3" /></a-form-item>
        <a-row :gutter="16"><a-col :span="12"><a-form-item label="排序"><a-input-number v-model:value="libraryForm.sortIndex" :min="0" :precision="0" /></a-form-item></a-col><a-col :span="12"><a-form-item label="启用"><a-switch v-model:checked="libraryForm.enabled" :disabled="libraryForm.id === 'standard'" /></a-form-item></a-col></a-row>
        <p v-if="libraryForm.id === 'standard'" class="muted">标准库保留为默认库，不能删除或停用。</p>
      </a-form>
    </a-modal>

    <a-modal v-model:open="categoryDialog" :title="categoryForm.id ? '编辑分类' : '新增分类'" :confirm-loading="saving" :mask-closable="false" @ok="saveCategory" @cancel="dialogError = ''">
      <a-alert v-if="dialogError" type="error" show-icon :message="dialogError" class="notice" />
      <a-form name="property-library-category" ref="categoryFormRef" :model="categoryForm" layout="vertical">
        <a-form-item label="分类名称" name="name" :rules="[{ required: true, whitespace: true, message: '请填写分类名称' }, { max: 64 }]" ><a-input v-model:value="categoryForm.name" :maxlength="64" /></a-form-item>
        <a-form-item label="上级分类"><a-select v-model:value="categoryForm.parentId" allow-clear placeholder="顶级分类" :options="categoryOptions(categories, categoryForm.id)" /></a-form-item>
        <a-form-item label="排序"><a-input-number v-model:value="categoryForm.sortIndex" :min="0" :precision="0" /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="itemDialog" :title="itemForm.id ? '编辑采集项' : '新增采集项'" :confirm-loading="saving" :mask-closable="false" @ok="saveItem" @cancel="dialogError = ''">
      <a-alert v-if="dialogError" type="error" show-icon :message="dialogError" class="notice" />
      <a-form name="property-library-item" ref="itemFormRef" :model="itemForm" layout="vertical">
        <a-form-item label="采集项名称" name="name" :rules="[{ required: true, whitespace: true, message: '请填写采集项名称' }, { max: 64 }]" ><a-input v-model:value="itemForm.name" :maxlength="64" placeholder="例如 A相电压" /></a-form-item>
        <a-form-item label="采集项 ID" name="code" :rules="codeRules"><a-input v-model:value="itemForm.code" :maxlength="64" placeholder="例如 Ua" /></a-form-item>
        <a-form-item label="分类"><a-select v-model:value="itemForm.categoryId" allow-clear show-search option-filter-prop="label" placeholder="未设置分类" :options="categoryOptions(categories)" /></a-form-item>
        <a-form-item label="备注"><a-textarea v-model:value="itemForm.remark" :rows="2" :maxlength="500" /></a-form-item>
        <a-row :gutter="16"><a-col :span="12"><a-form-item label="排序"><a-input-number v-model:value="itemForm.sortIndex" :min="0" :precision="0" /></a-form-item></a-col><a-col :span="12"><a-form-item label="启用"><a-switch v-model:checked="itemForm.enabled" /></a-form-item></a-col></a-row>
        <a-collapse v-if="itemForm.source && Object.keys(itemForm.source).length" ghost><a-collapse-panel key="source" header="查看导入来源"><a-descriptions :column="1" size="small"><a-descriptions-item label="来源文件">{{ itemForm.source.file || '—' }}</a-descriptions-item><a-descriptions-item label="工作表">{{ itemForm.source.sheet || '—' }}</a-descriptions-item><a-descriptions-item label="原始行号">{{ itemForm.source.row ?? itemForm.source.rowNumber ?? '—' }}</a-descriptions-item><a-descriptions-item label="原始编号（Fid）">{{ itemForm.source.fid ?? '—' }}</a-descriptions-item></a-descriptions></a-collapse-panel></a-collapse>
      </a-form>
    </a-modal>

    <a-modal v-model:open="batchDialog" :title="batchMode === 'adopt' ? '采纳到标准库' : '批量设置分类'" :confirm-loading="saving" :mask-closable="false" :ok-button-props="{ disabled: batchLoading || !!batchError || !!batchResult }" @ok="saveBatch" @cancel="closeBatch">
      <a-alert v-if="batchError" type="error" show-icon :message="batchError" class="notice" />
      <a-alert v-if="batchMode === 'adopt'" type="info" show-icon message="复制所选采集项到标准库，原项目库保留。标准库中同 ID 的内容不会被覆盖。" class="notice" />
      <p>已选择 {{ selectedKeys.length }} 项</p>
      <a-form layout="vertical"><a-form-item :label="batchMode === 'adopt' ? '标准库中的目标分类' : '目标分类'"><a-select v-model:value="batchCategoryId" :loading="batchLoading" allow-clear show-search option-filter-prop="label" :options="categoryOptions(batchCategories)" placeholder="未设置分类" /></a-form-item></a-form>
      <a-alert v-if="batchResult" type="success" show-icon :message="`新增 ${batchResult.created} 项，跳过 ${batchResult.skipped} 项`" />
      <div v-if="batchResult?.conflicts?.length" class="batch-conflicts"><strong>已存在的 ID（保留标准库原值）</strong><p v-for="(row, index) in batchResult.conflicts" :key="index">{{ row.code }}：{{ row.name }} → 已有 {{ row.existingName || row.name }}</p></div>
    </a-modal>
    <ImportDialog v-if="importVisible && currentLibrary" :library="currentLibrary" @close="importVisible = false" @imported="refreshCurrent" />
  </j-page-container>
</template>

<script setup lang="ts" name="PropertyLibrary">
import { computed, onMounted, ref } from 'vue'
import { Empty, message } from 'ant-design-vue'
import type { TablePaginationConfig } from 'ant-design-vue'
import {
  getPropertyLibraries, createPropertyLibrary, updatePropertyLibrary, deletePropertyLibrary,
  getPropertyLibraryCategories, createPropertyLibraryCategory, updatePropertyLibraryCategory, deletePropertyLibraryCategory,
  queryPropertyLibraryItems, createPropertyLibraryItem, updatePropertyLibraryItem, deletePropertyLibraryItem,
  movePropertyLibraryItems, adoptPropertyLibraryItems,
} from '../../../api/propertyLibrary'
import type { PropertyLibrary, PropertyLibraryCategory, PropertyLibraryItem, PropertyLibraryImportResult } from '../../../api/propertyLibrary'
import { categoryOptions, errorMessage, readResult } from './utils'
import ImportDialog from './ImportDialog.vue'

const libraries = ref<PropertyLibrary[]>([])
const libraryId = ref('')
const categories = ref<PropertyLibraryCategory[]>([])
const categoryId = ref<string>()
const items = ref<PropertyLibraryItem[]>([])
const libraryLoading = ref(false), categoryLoading = ref(false), itemLoading = ref(false)
const libraryError = ref(''), categoryError = ref(''), itemError = ref('')
const keyword = ref(''), enabledFilter = ref('all'), includeChildren = ref(true)
const pageIndex = ref(0), pageSize = ref(20), total = ref(0)
const selectedKeys = ref<string[]>([])
const saving = ref(false), dialogError = ref('')
const libraryDialog = ref(false), categoryDialog = ref(false), itemDialog = ref(false), importVisible = ref(false)
const libraryFormRef = ref(), categoryFormRef = ref(), itemFormRef = ref()
const libraryForm = ref<Partial<PropertyLibrary>>({})
const categoryForm = ref<Partial<PropertyLibraryCategory>>({})
const itemForm = ref<Partial<PropertyLibraryItem>>({})
const batchDialog = ref(false), batchLoading = ref(false), batchError = ref('')
const batchMode = ref<'category' | 'adopt'>('category')
const batchCategoryId = ref<string>()
const batchCategories = ref<PropertyLibraryCategory[]>([])
const batchResult = ref<PropertyLibraryImportResult>()
let itemRequest = 0
let categoryRequest = 0
let libraryRequest = 0

const currentLibrary = computed(() => libraries.value.find(item => item.id === libraryId.value))
const findCategory = (nodes: PropertyLibraryCategory[], id?: string): PropertyLibraryCategory | undefined => {
  for (const node of nodes) { if (node.id === id) return node; const found = findCategory(node.children || [], id); if (found) return found }
}
const currentCategory = computed(() => findCategory(categories.value, categoryId.value))
const labels = computed(() => new Map(categoryOptions(categories.value).map(option => [option.value, option.label])))
const categoryLabel = (id?: string) => id ? labels.value.get(id) || '分类已变更' : '未设置分类'
const statusOptions = [{ label: '全部状态', value: 'all' }, { label: '启用', value: 'enabled' }, { label: '停用', value: 'disabled' }]
const codeRules = [{ required: true, whitespace: true, message: '请填写 ID / 编码' }, { pattern: /^(?![Nn][Oo][Nn][Ee]$)[a-zA-Z0-9_]+$/, message: '仅支持字母、数字和下划线，且不能为 None' }, { max: 64, message: '不能超过 64 个字符' }]
const libraryCodeRules = [{ required: true, whitespace: true, message: '请填写项目库编码' }, { pattern: /^[a-zA-Z0-9_-]+$/, message: '仅支持字母、数字、下划线和短横线' }, { max: 64, message: '不能超过 64 个字符' }]
const columns = [
  { title: '采集项名称', dataIndex: 'name', key: 'name', width: 210 },
  { title: '采集项 ID', dataIndex: 'code', key: 'code', width: 160 },
  { title: '分类', key: 'categoryId', width: 180 },
  { title: '状态', key: 'enabled', width: 90 },
  { title: '排序', dataIndex: 'sortIndex', key: 'sortIndex', width: 80 },
  { title: '操作', key: 'action', width: 200, fixed: 'right' as const },
]

const loadLibraries = async (preferred?: string) => {
  const requestId = ++libraryRequest
  libraryLoading.value = true
  libraryError.value = ''
  try {
    const data = readResult(await getPropertyLibraries()) || []
    if (requestId !== libraryRequest) return
    libraries.value = data
    const desired = preferred || libraryId.value
    const next = data.find(item => item.id === desired)?.id || data.find(item => item.id === 'standard')?.id || data[0]?.id || ''
    if (next !== libraryId.value) await selectLibrary(next)
  } catch (e) { if (requestId === libraryRequest) libraryError.value = errorMessage(e, '项目库加载失败') }
  finally { if (requestId === libraryRequest) libraryLoading.value = false }
}
const loadCategories = async () => {
  const id = libraryId.value, requestId = ++categoryRequest
  if (!id) { categories.value = []; return }
  categoryLoading.value = true
  categoryError.value = ''
  try {
    const data = readResult(await getPropertyLibraryCategories(id)) || []
    if (id !== libraryId.value || requestId !== categoryRequest) return
    categories.value = data
    if (categoryId.value && categoryId.value !== '__uncategorized__' && !findCategory(data, categoryId.value)) { categoryId.value = undefined; pageIndex.value = 0; await loadItems() }
  } catch (e) { if (requestId === categoryRequest) categoryError.value = errorMessage(e, '分类加载失败') }
  finally { if (requestId === categoryRequest) categoryLoading.value = false }
}
const loadItems = async () => {
  const id = libraryId.value, requestId = ++itemRequest
  if (!id) { items.value = []; total.value = 0; return }
  itemLoading.value = true
  itemError.value = ''
  try {
    const data = readResult(await queryPropertyLibraryItems({ libraryId: id, categoryId: categoryId.value, includeChildren: includeChildren.value, keyword: keyword.value.trim() || undefined, enabled: enabledFilter.value === 'all' ? undefined : enabledFilter.value === 'enabled', pageIndex: pageIndex.value, pageSize: pageSize.value }))
    if (id !== libraryId.value || requestId !== itemRequest) return
    items.value = data?.data || []
    total.value = data?.total || 0
    if (!items.value.length && pageIndex.value > 0 && total.value > 0) { pageIndex.value = Math.max(0, Math.ceil(total.value / pageSize.value) - 1); await loadItems() }
  } catch (e) { if (requestId === itemRequest) { items.value = []; itemError.value = errorMessage(e, '采集项加载失败') } }
  finally { if (requestId === itemRequest) itemLoading.value = false }
}
const selectLibrary = async (id: string) => {
  if (id === libraryId.value) return
  libraryId.value = id
  categoryId.value = undefined
  categories.value = []; items.value = []; total.value = 0; selectedKeys.value = []
  keyword.value = ''; enabledFilter.value = 'all'; pageIndex.value = 0
  await Promise.all([loadCategories(), loadItems()])
}
const selectCategory = (id?: string) => { categoryId.value = id; searchItems() }
const onCategorySelect = (keys: (string | number)[]) => selectCategory(keys.length ? String(keys[0]) : undefined)
const searchItems = () => { pageIndex.value = 0; selectedKeys.value = []; void loadItems() }
const tableChange = (pagination: TablePaginationConfig) => { pageIndex.value = (pagination.current || 1) - 1; pageSize.value = pagination.pageSize || 20; void loadItems() }
const refreshCurrent = async () => { await Promise.all([loadCategories(), loadItems()]) }
const refresh = async () => { await loadLibraries(); await refreshCurrent() }

const openLibrary = (library?: PropertyLibrary) => {
  libraryForm.value = library ? { ...library } : { code: '', name: '', description: '', enabled: true, sortIndex: 0 }
  dialogError.value = ''; libraryDialog.value = true
}
const openCategory = (category?: PropertyLibraryCategory) => {
  categoryForm.value = category ? { id: category.id, libraryId: category.libraryId, parentId: category.parentId, name: category.name, sortIndex: category.sortIndex } : { libraryId: libraryId.value, parentId: categoryId.value === '__uncategorized__' ? undefined : categoryId.value, name: '', sortIndex: 0 }
  dialogError.value = ''; categoryDialog.value = true
}
const openItem = (item?: PropertyLibraryItem) => {
  itemForm.value = item ? { ...item } : { libraryId: libraryId.value, categoryId: categoryId.value === '__uncategorized__' ? undefined : categoryId.value, code: '', name: '', enabled: true, sortIndex: 0, remark: '' }
  dialogError.value = ''; itemDialog.value = true
}
const saveLibrary = async () => {
  if (saving.value) return
  try { await libraryFormRef.value.validate() } catch { return }
  saving.value = true; dialogError.value = ''
  try {
    const data = { ...libraryForm.value, name: libraryForm.value.name!.trim(), code: libraryForm.value.code!.trim() } as PropertyLibrary
    if (data.id) readResult(await updatePropertyLibrary(data.id, data))
    else { const created = readResult(await createPropertyLibrary(data)); data.id = created.id }
    libraryDialog.value = false; message.success('项目库已保存'); await loadLibraries(data.id)
  } catch (e) { dialogError.value = errorMessage(e) }
  finally { saving.value = false }
}
const saveCategory = async () => {
  if (saving.value) return
  try { await categoryFormRef.value.validate() } catch { return }
  saving.value = true; dialogError.value = ''
  try {
    const data = { ...categoryForm.value, parentId: categoryForm.value.parentId || '', name: categoryForm.value.name!.trim() } as PropertyLibraryCategory
    readResult(data.id ? await updatePropertyLibraryCategory(data.id, data) : await createPropertyLibraryCategory(data))
    categoryDialog.value = false; message.success('分类已保存'); await loadCategories()
  } catch (e) { dialogError.value = errorMessage(e) }
  finally { saving.value = false }
}
const saveItem = async () => {
  if (saving.value) return
  try { await itemFormRef.value.validate() } catch { return }
  saving.value = true; dialogError.value = ''
  try {
    const data = { ...itemForm.value, categoryId: itemForm.value.categoryId || '', code: itemForm.value.code!.trim(), name: itemForm.value.name!.trim() } as PropertyLibraryItem
    readResult(data.id ? await updatePropertyLibraryItem(data.id, data) : await createPropertyLibraryItem(data))
    itemDialog.value = false; message.success('采集项已保存'); await loadItems()
  } catch (e) { dialogError.value = errorMessage(e) }
  finally { saving.value = false }
}
const perform = async (action: () => Promise<unknown>, refreshFn: () => Promise<void>, success: string) => {
  try { await action(); message.success(success); await refreshFn() } catch (e) { message.error(errorMessage(e)) }
}
const removeLibrary = (library: PropertyLibrary) => {
  if (library.id === 'standard') return
  return perform(async () => readResult(await deletePropertyLibrary(library.id)), async () => { await loadLibraries() }, '项目库已删除')
}
const removeCategory = (category: PropertyLibraryCategory) => perform(async () => readResult(await deletePropertyLibraryCategory(category.id)), refreshCurrent, '分类已删除')
const removeItem = (item: PropertyLibraryItem) => perform(async () => { readResult(await deletePropertyLibraryItem(item.id)); selectedKeys.value = selectedKeys.value.filter(id => id !== item.id) }, loadItems, '采集项已删除')
const toggleItem = (item: PropertyLibraryItem) => perform(async () => readResult(await updatePropertyLibraryItem(item.id, { ...item, enabled: !item.enabled })), loadItems, item.enabled ? '采集项已停用' : '采集项已启用')

const openBatch = async (mode: 'category' | 'adopt') => {
  batchMode.value = mode; batchCategoryId.value = undefined; batchResult.value = undefined; batchError.value = ''; batchDialog.value = true
  batchCategories.value = mode === 'category' ? categories.value : []
  if (mode === 'adopt') {
    batchLoading.value = true
    try { batchCategories.value = readResult(await getPropertyLibraryCategories('standard')) || [] }
    catch (e) { batchError.value = errorMessage(e, '标准库分类加载失败，请关闭后重试') }
    finally { batchLoading.value = false }
  }
}
const saveBatch = async () => {
  if (saving.value || batchLoading.value || !selectedKeys.value.length || batchResult.value) return
  saving.value = true; batchError.value = ''
  try {
    if (batchMode.value === 'adopt') {
      batchResult.value = readResult(await adoptPropertyLibraryItems([...selectedKeys.value], batchCategoryId.value || ''))
      message.success('采纳处理完成')
    } else {
      readResult(await movePropertyLibraryItems(libraryId.value, [...selectedKeys.value], batchCategoryId.value || ''))
      batchDialog.value = false; selectedKeys.value = []; message.success('分类已更新'); await loadItems()
    }
  } catch (e) { batchError.value = errorMessage(e) }
  finally { saving.value = false }
}
const closeBatch = () => { batchError.value = ''; if (batchResult.value) selectedKeys.value = [] }
onMounted(() => loadLibraries())
</script>

<style scoped lang="less">
.property-library-page { padding: 24px; background: var(--app-surface, #fff); border-radius: 8px; min-height: 620px; }
.page-heading, .library-title, .item-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
h2, h3 { margin: 0 0 6px; color: var(--app-text); } h2 { font-size: 22px; } h3 { font-size: 18px; }
p { color: var(--app-text-secondary); margin: 0; line-height: 1.6; }
.page-heading { padding-bottom: 22px; border-bottom: 1px solid var(--app-border, #e8e8e8); }
.library-layout { display: flex; gap: 24px; padding-top: 20px; }
.library-sidebar { flex: 0 0 238px; border-right: 1px solid var(--app-border, #e8e8e8); padding-right: 16px; }
.sidebar-heading { display: flex; align-items: center; justify-content: space-between; min-height: 36px; }
.library-list { max-height: 260px; overflow: auto; margin-top: 8px; }
.library-option, .category-all { appearance: none; border: 0; background: transparent; color: var(--app-text); width: 100%; text-align: left; padding: 10px 12px; border-radius: 6px; cursor: pointer; }
.library-option { display: flex; align-items: center; gap: 8px; margin-bottom: 4px; }
.library-option span:first-child { flex: 1; overflow-wrap: anywhere; }
.library-option:hover, .category-all:hover { background: var(--app-hover, rgba(22,119,255,.06)); }
.library-option.active, .category-all.active { background: var(--app-primary-bg, rgba(22,119,255,.1)); color: var(--ant-color-primary, #1677ff); font-weight: 600; }
.category-heading { border-top: 1px solid var(--app-border, #e8e8e8); margin-top: 22px; padding-top: 16px; }
.category-actions { display: flex; gap: 8px; margin-top: 16px; }
.library-content { min-width: 0; flex: 1; }
.library-title { margin-bottom: 22px; align-items: flex-start; }
.item-toolbar { margin-bottom: 16px; flex-wrap: wrap; }
.selection-bar { display: flex; align-items: center; gap: 12px; padding: 10px 14px; background: var(--app-primary-bg, rgba(22,119,255,.08)); margin-bottom: 12px; border-radius: 6px; }
.item-code { font-family: Consolas, monospace; color: var(--app-text); }
.notice { margin: 12px 0; }
.empty-content { display: flex; align-items: center; justify-content: center; }
.muted { color: var(--app-text-secondary); }
.source-detail { white-space: pre-wrap; overflow-wrap: anywhere; max-height: 220px; overflow: auto; }
.batch-conflicts { margin-top: 16px; max-height: 200px; overflow: auto; }
@media (max-width: 1050px) { .property-library-page { padding: 16px; } .library-layout { gap: 16px; } .library-sidebar { flex-basis: 195px; } .library-title { flex-wrap: wrap; } }
@media (max-width: 760px) { .library-layout { flex-direction: column; } .library-sidebar { flex-basis: auto; border-right: 0; border-bottom: 1px solid var(--app-border, #e8e8e8); padding: 0 0 16px; } .library-list { max-height: 150px; } .selection-bar { flex-wrap: wrap; } }
</style>
