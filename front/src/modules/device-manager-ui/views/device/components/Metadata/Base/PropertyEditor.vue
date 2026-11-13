<template>
  <a-modal :open="open" :title="title" width="800px" :destroy-on-close="true" @cancel="cancel" @ok="submit">
    <a-form v-if="draft" ref="formRef" :model="draft" :rules="rules" layout="vertical" class="property-editor">
      <a-row :gutter="16">
        <a-col :xs="24" :sm="12"><a-form-item label="属性名称" name="name">
          <LibraryItemInput v-model:value="draft.name" field="name" :library-id="libraryId" :existing-codes="existingCodes" @select="selectItem" />
        </a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item label="属性 ID" name="id">
          <LibraryItemInput v-model:value="draft.id" field="id" :library-id="libraryId" :existing-codes="existingCodes" @select="selectItem" />
        </a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item label="数据类型" name="valueType">
          <div class="type-control">
            <TypeSelect v-model:value="draft.valueType" @value-change="typeChange" />
            <StringParams v-if="['string','password'].includes(draft.valueType.type)" v-model:value="draft.valueType" />
            <DateParams v-else-if="draft.valueType.type === 'date'" v-model:value="draft.valueType.format" />
            <FileParams v-else-if="draft.valueType.type === 'file'" v-model:value="draft.valueType" />
            <EnumParams v-else-if="draft.valueType.type === 'enum'" v-model:value="draft.valueType.elements" />
            <BooleanParams v-else-if="draft.valueType.type === 'boolean'" v-model:falseText="draft.valueType.falseText" v-model:falseValue="draft.valueType.falseValue" v-model:trueText="draft.valueType.trueText" v-model:trueValue="draft.valueType.trueValue" />
            <ObjectParams v-else-if="draft.valueType.type === 'object'" v-model:value="draft.valueType.properties" :level="2" />
            <ArrayParams v-else-if="draft.valueType.type === 'array'" v-model:value="draft.valueType.elementType" :level="2" />
          </div>
        </a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item label="单位"><UnitSelect v-model:value="draft.valueType.unit" /></a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item label="来源" name="expands"><Source v-model:value="draft.expands" :target="target" :record="draft" /></a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item label="操作类型"><a-select v-model:value="draft.expands.type" mode="multiple" :options="operationTypeOptions" /></a-form-item></a-col>
        <a-col v-if="groupOptions.length" :xs="24" :sm="12"><a-form-item label="属性分组"><a-select :value="draft.expands.groupId" :options="groupOptions" @change="setGroup" /></a-form-item></a-col>
      </a-row>
      <a-collapse class="property-editor-advanced">
        <a-collapse-panel key="protocol" header="协议与高级配置">
          <a-row :gutter="16">
            <a-col :xs="24" :sm="12"><a-form-item label="排序"><a-input-number v-model:value="draft.sortsIndex" :min="0" style="width:100%" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="寄存器地址"><a-input v-model:value="draft.expands.registerAddr" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="寄存器数量"><a-input v-model:value="draft.expands.registerNum" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="解析方法"><a-select v-model:value="draft.expands.parseMethod" :options="parseMethodOptions" show-search allow-clear /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="功能码"><a-input v-model:value="draft.expands.functionCode" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="精度"><a-input-number v-model:value="draft.valueType.scale" :min="0" :max="99" style="width:100%" /></a-form-item></a-col>
            <a-col :xs="24" :sm="12"><a-form-item label="公式"><a-input v-model:value="draft.expands.formula" /></a-form-item></a-col>
          </a-row>
        </a-collapse-panel>
      </a-collapse>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, provide, ref, watch } from 'vue'
import { omit } from 'lodash-es'
import type { FormInstance } from 'ant-design-vue'
import { TypeSelect, UnitSelect, StringParams, DateParams, FileParams, EnumParams, BooleanParams, ObjectParams, ArrayParams } from '../../../../../components/Metadata'
import { FULL_SCREEN, TABLE_WRAPPER } from '../../../../../components/Metadata/consts'
import { Source } from './components'
import LibraryItemInput from '../../../../../components/PropertyLibrary/LibraryItemInput.vue'
import type { PropertyLibraryItem } from '../../../../../api/propertyLibrary'
import { operationTypeOptions, parseMethodOptions, validatorConfig } from './columns'

const props = withDefaults(defineProps<{ open: boolean, record?: Record<string, any>, libraryId: string, existingCodes?: string[], groupOptions?: Array<{value: string | number, label: string}>, target: 'product' | 'device' }>(), { existingCodes: () => [], groupOptions: () => [] })
const emit = defineEmits<{ (event: 'update:open', value: boolean): void, (event: 'save', value: Record<string, any>): void }>()
const formRef = ref<FormInstance>()
const draft = ref<Record<string, any>>()
const title = computed(() => props.record?.id ? '编辑采集项' : '自定义采集项')
// Existing configuration widgets expect the table popup and fullscreen context.
provide(TABLE_WRAPPER, computed(() => document.body))
provide(FULL_SCREEN, ref(true))
watch(() => props.open, value => {
  if (!value) return
  const copy = JSON.parse(JSON.stringify(props.record || {}))
  copy.expands = copy.expands || { source: 'device', type: ['report'] }
  copy.valueType = copy.valueType || {}
  draft.value = copy
}, { immediate: true })

const rules = {
  name: [{ required: true, message: '请输入属性名称' }, { max: 64, message: '名称不能超过 64 个字符' }],
  id: [{ required: true, message: '请输入属性 ID' }, { max: 64, message: 'ID 不能超过 64 个字符' }, { pattern: /^[a-zA-Z0-9_]+$/, message: 'ID 只能包含字母、数字和下划线' }, { validator: async (_: any, value: string) => { if (props.existingCodes.includes(value)) throw new Error('属性 ID 已存在') } }],
  valueType: [{ validator: async (_: any, value: any) => { if (!value?.type) throw new Error('请选择数据类型'); await validatorConfig(value, true) } }],
  expands: [{ validator: async (_: any, value: any) => { if (!value?.source) throw new Error('请选择来源'); if (value.source === 'device' && !value.type?.length) throw new Error('请选择操作类型') } }],
}
function selectItem(item: PropertyLibraryItem) { if (draft.value && !props.existingCodes.includes(item.code)) { draft.value.id = item.code; draft.value.name = item.name } }
function setGroup(value: string | number) { if (draft.value) { draft.value.expands.groupId = value; draft.value.expands.groupName = props.groupOptions.find(item => item.value === value)?.label } }
function typeChange() { if (draft.value) draft.value.expands = omit(draft.value.expands, ['metrics', 'expands']) }
function cancel() { emit('update:open', false) }
async function submit() {
  try { await formRef.value?.validate(); if (draft.value) { emit('save', JSON.parse(JSON.stringify(draft.value))); emit('update:open', false) } } catch { /* The form displays its validation errors. */ }
}
</script>

<style scoped>
.property-editor { max-height: 65vh; overflow-y: auto; padding: 4px 4px 0; }
.type-control { display: flex; gap: 8px; align-items: center; }
.type-control > :first-child { flex: 1; min-width: 0; }
.property-editor-advanced { margin-bottom: 8px; }
</style>
