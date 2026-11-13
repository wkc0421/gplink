<template>
  <div class="library-item-input">
    <AutoComplete
      :value="value"
      :options="options"
      :disabled="disabled"
      :placeholder="placeholder || '搜索名称 / ID，或自定义填写'"
      :get-popup-container="getPopupContainer"
      style="width: 100%"
      @change="change"
      @search="inputSearch"
      @focus="search(String(value || ''))"
      @select="select"
    >
      <Input :aria-label="field === 'id' ? '采集项 ID' : '采集项名称'" />
    </AutoComplete>
    <button v-if="error && !disabled" type="button" class="library-input-retry" :title="error" @click="search(String(value || ''), true)">查询失败，重试</button>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { AutoComplete, Form, Input } from 'ant-design-vue'
import { queryPropertyLibraryItems } from '../../api/propertyLibrary'
import type { PropertyLibraryItem } from '../../api/propertyLibrary'

const props = withDefaults(defineProps<{
  value?: string
  field: 'id' | 'name'
  libraryId: string
  existingCodes?: string[]
  disabled?: boolean
  placeholder?: string
  getPopupContainer?: (node: HTMLElement) => HTMLElement
}>(), { existingCodes: () => [], disabled: false })
const emit = defineEmits<{
  (event: 'update:value', value: string): void
  (event: 'select', item: PropertyLibraryItem): void
  (event: 'change'): void
}>()
const form = Form.useInjectFormItemContext()
const items = ref<PropertyLibraryItem[]>([])
const error = ref('')
let generation = 0
let timer: ReturnType<typeof setTimeout> | undefined
const options = computed(() => items.value.map(item => ({
  value: item.code,
  label: `${item.name} · ${item.code}${props.existingCodes.includes(item.code) ? '（已添加）' : ''}`,
  disabled: props.existingCodes.includes(item.code),
  item,
})))

function change(value: string, option: { item?: PropertyLibraryItem }) {
  // AutoComplete also emits change for a selection; select applies both fields atomically.
  if (props.disabled || option?.item) return
  emit('update:value', value)
  emit('change')
  form.onFieldChange()
}

function select(_value: string, option: { item: PropertyLibraryItem }) {
  if (props.disabled || props.existingCodes.includes(option.item.code)) return
  emit('select', option.item)
  emit('change')
  form.onFieldChange()
}

function inputSearch(value: string) {
  if (props.disabled) return
  // Typing an exact code can also carry a matching option in AutoComplete.change.
  // search is emitted only for text input, so keep that custom draft in sync too.
  emit('update:value', value)
  emit('change')
  form.onFieldChange()
  search(value)
}

function search(keyword: string, immediate = false) {
  clearTimeout(timer)
  const request = ++generation
  items.value = []
  if (props.disabled || !props.libraryId) return
  timer = setTimeout(async () => {
    try {
      const result = await queryPropertyLibraryItems({ libraryId: props.libraryId, keyword, enabled: true, includeChildren: true, pageIndex: 0, pageSize: 30 })
      if (request !== generation) return
      if (!result.success) throw new Error(result.message || '采集项库查询失败')
      items.value = result.result?.data || []
      error.value = ''
    } catch (reason: any) {
      if (request === generation) error.value = reason?.message || '采集项库查询失败；仍可自定义填写'
    }
  }, immediate ? 0 : 200)
}
watch(() => [props.libraryId, props.disabled], () => { generation++; clearTimeout(timer); items.value = []; error.value = '' })
onBeforeUnmount(() => { generation++; clearTimeout(timer) })
</script>

<style scoped>
.library-item-input { width: 100%; position: relative; }
.library-input-retry { border: 0; background: transparent; color: var(--app-text-secondary); font-size: 11px; padding: 0; cursor: pointer; }
</style>
