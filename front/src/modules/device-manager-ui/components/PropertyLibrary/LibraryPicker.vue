<template>
  <a-modal :open="open" title="从采集项库添加" :width="1080" :get-container="getContainer" :mask-closable="false" @cancel="close">
    <div class="library-picker-filters">
      <a-select v-model:value="selectedLibrary" aria-label="选择项目库" :options="libraryOptions" :loading="librariesLoading" placeholder="选择项目库" @change="changeLibrary" />
      <a-input-search v-model:value="keyword" aria-label="搜索采集项名称或 ID" placeholder="搜索采集项名称或 ID" allow-clear @search="search" @change="scheduleSearch" />
    </div>
    <a-alert v-if="libraryError" type="error" :message="libraryError" show-icon>
      <template #action><a-button size="small" @click="loadLibraries">重试</a-button></template>
    </a-alert>
    <div class="library-picker-content">
      <aside>
        <div class="library-picker-label">分类（包含子分类）</div>
        <a-spin :spinning="categoriesLoading">
          <a-alert v-if="categoryError" type="warning" :message="categoryError">
            <template #action><a-button size="small" @click="loadCategories">重试</a-button></template>
          </a-alert>
          <a-tree :tree-data="treeData" :selected-keys="[categoryId || '__all']" :expanded-keys="expandedKeys" :field-names="{ title: 'name', key: 'id', children: 'children' }" @expand="expandCategories" @select="selectCategory" />
        </a-spin>
      </aside>
      <section>
        <a-alert v-if="itemError" type="error" :message="itemError" show-icon>
          <template #action><a-button size="small" @click="loadItems">重试</a-button></template>
        </a-alert>
        <a-table
          size="small"
          row-key="id"
          :columns="columns"
          :data-source="items"
          :loading="itemsLoading"
          :row-selection="rowSelection"
          :pagination="{ current: pageIndex + 1, pageSize, total, showSizeChanger: false, showTotal: (count: number) => `共 ${count} 项` }"
          :scroll="{ y: 340, x: 560 }"
          @change="changePage"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'status'">
              <a-tag v-if="existingCodes.includes(record.code)">已添加</a-tag>
              <span v-else>可添加</span>
            </template>
          </template>
          <template #emptyText>{{ selectedLibrary ? '此分类下没有匹配的采集项，可切换分类或自定义填写' : '暂无可用项目库，仍可自定义填写' }}</template>
        </a-table>
      </section>
    </div>
    <template #footer>
      <div class="library-picker-footer">
        <span>已选 {{ selected.length }} 项；添加后请继续配置数据类型、单位和采集参数。</span>
        <a-space><a-button @click="close">取消</a-button><a-button type="primary" :disabled="disabled || !selected.length" @click="add">加入物模型</a-button></a-space>
      </div>
    </template>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { getPropertyLibraries, getPropertyLibraryCategories, queryPropertyLibraryItems } from '../../api/propertyLibrary'
import type { PropertyLibrary, PropertyLibraryCategory, PropertyLibraryItem } from '../../api/propertyLibrary'

const props = withDefaults(defineProps<{
  open: boolean
  libraryId?: string
  existingCodes?: string[]
  disabled?: boolean
  getContainer?: () => HTMLElement
}>(), { libraryId: 'standard', existingCodes: () => [], disabled: false })
const emit = defineEmits<{
  (event: 'update:open', value: boolean): void
  (event: 'update:libraryId', value: string): void
  (event: 'add', value: PropertyLibraryItem[]): void
}>()
const libraries = ref<PropertyLibrary[]>([])
const categories = ref<PropertyLibraryCategory[]>([])
const items = ref<PropertyLibraryItem[]>([])
const selectedMap = ref<Record<string, PropertyLibraryItem>>({})
const selected = computed(() => Object.values(selectedMap.value).filter(item => !props.existingCodes.includes(item.code)))
const selectedLibrary = ref(props.libraryId)
const categoryId = ref<string>()
const expandedKeys = ref(['__all'])
const keyword = ref('')
const pageIndex = ref(0)
const pageSize = 20
const total = ref(0)
const librariesLoading = ref(false), categoriesLoading = ref(false), itemsLoading = ref(false)
const libraryError = ref(''), categoryError = ref(''), itemError = ref('')
let libraryRequest = 0, categoryRequest = 0, itemRequest = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined
const libraryOptions = computed(() => libraries.value.map(item => ({ label: item.name, value: item.id })))
const treeData = computed(() => [{ id: '__all', name: '全部分类', children: [...categories.value, { id: '__uncategorized__', name: '未设置分类' }] }])
const columns = [{ title: '采集项名称', dataIndex: 'name', width: 220 }, { title: '采集项 ID', dataIndex: 'code', width: 180 }, { title: '状态', key: 'status', width: 100 }]
const rowSelection = computed(() => ({
  selectedRowKeys: selected.value.map(item => item.id),
  preserveSelectedRowKeys: true,
  getCheckboxProps: (item: PropertyLibraryItem) => ({ disabled: props.disabled || props.existingCodes.includes(item.code) }),
  onChange(keys: string[], rows: PropertyLibraryItem[]) {
    const known = { ...selectedMap.value }
    rows.filter(Boolean).forEach(item => { known[item.id] = item })
    selectedMap.value = Object.fromEntries(keys.filter(key => known[key] && !props.existingCodes.includes(known[key].code)).map(key => [key, known[key]]))
  },
}))

async function loadLibraries() {
  const request = ++libraryRequest
  librariesLoading.value = true
  libraryError.value = ''
  try {
    const response = await getPropertyLibraries(true)
    if (request !== libraryRequest) return
    if (!response.success) throw new Error(response.message || '项目库加载失败')
    libraries.value = response.result || []
    const id = libraries.value.some(item => item.id === props.libraryId)
      ? props.libraryId : libraries.value.find(item => item.code === 'standard')?.id || libraries.value[0]?.id || ''
    selectedLibrary.value = id
    await changeLibrary(id)
  } catch (reason: any) {
    if (request === libraryRequest) libraryError.value = reason?.message || '项目库加载失败，请重试；仍可使用自定义采集项'
  } finally { if (request === libraryRequest) librariesLoading.value = false }
}

async function loadCategories() {
  const request = ++categoryRequest, id = selectedLibrary.value
  categories.value = []
  categoryError.value = ''
  if (!id) return
  categoriesLoading.value = true
  try {
    const response = await getPropertyLibraryCategories(id)
    if (request !== categoryRequest) return
    if (!response.success) throw new Error(response.message || '分类加载失败')
    categories.value = response.result || []
  } catch (reason: any) { if (request === categoryRequest) categoryError.value = reason?.message || '分类加载失败' }
  finally { if (request === categoryRequest) categoriesLoading.value = false }
}

async function loadItems() {
  const request = ++itemRequest, id = selectedLibrary.value
  items.value = []; total.value = 0; itemError.value = ''
  if (!id) return
  itemsLoading.value = true
  try {
    const response = await queryPropertyLibraryItems({ libraryId: id, categoryId: categoryId.value, includeChildren: true, keyword: keyword.value.trim(), enabled: true, pageIndex: pageIndex.value, pageSize })
    if (request !== itemRequest) return
    if (!response.success) throw new Error(response.message || '采集项加载失败')
    items.value = response.result?.data || []
    total.value = response.result?.total || 0
  } catch (reason: any) { if (request === itemRequest) itemError.value = reason?.message || '采集项加载失败' }
  finally { if (request === itemRequest) itemsLoading.value = false }
}

async function changeLibrary(id: string) {
  selectedLibrary.value = id
  selectedMap.value = {}; categoryId.value = undefined; keyword.value = ''; pageIndex.value = 0
  expandedKeys.value = ['__all']
  emit('update:libraryId', id)
  await Promise.all([loadCategories(), loadItems()])
}
function selectCategory(keys: string[]) { categoryId.value = keys[0] && keys[0] !== '__all' ? keys[0] : undefined; pageIndex.value = 0; loadItems() }
function expandCategories(keys: (string | number)[]) { expandedKeys.value = keys.map(String) }
function search() { clearTimeout(searchTimer); pageIndex.value = 0; loadItems() }
function scheduleSearch() { clearTimeout(searchTimer); searchTimer = setTimeout(search, 250) }
function changePage(pagination: { current?: number }) { pageIndex.value = (pagination.current || 1) - 1; loadItems() }
function close() { emit('update:open', false) }
function add() { if (props.disabled) return; emit('add', selected.value); close() }
function invalidate() { libraryRequest++; categoryRequest++; itemRequest++; clearTimeout(searchTimer) }
watch(() => props.open, open => { if (open) { selectedMap.value = {}; loadLibraries() } else invalidate() }, { immediate: true })
onBeforeUnmount(invalidate)
</script>

<style scoped>
.library-picker-filters { display: flex; gap: 12px; margin-bottom: 16px; }
.library-picker-filters > :first-child { width: 280px; flex-shrink: 0; }
.library-picker-content { display: grid; grid-template-columns: 230px minmax(0, 1fr); gap: 16px; min-height: 280px; }
.library-picker-content aside { border-right: 1px solid var(--app-border, #ddd); padding-right: 12px; max-height: 430px; overflow: auto; }
.library-picker-label { margin-bottom: 12px; color: var(--app-text-secondary); }
.library-picker-footer { display: flex; justify-content: space-between; align-items: center; gap: 16px; text-align: left; }
@media (max-width: 720px) { .library-picker-content { grid-template-columns: 1fr; } .library-picker-content aside { max-height: 160px; border-right: 0; } .library-picker-filters, .library-picker-footer { flex-wrap: wrap; } }
</style>
