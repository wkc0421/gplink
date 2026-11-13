<template>
  <a-modal :open="open" :title="title" width="800px" :get-container="getContainer" :destroy-on-close="true" :ok-button-props="{ disabled: inherited }" @cancel="cancel" @ok="submit">
    <a-form v-if="draft" ref="formRef" :model="draft" :rules="rules" layout="vertical" class="definition-editor">
      <a-row :gutter="16">
        <a-col :xs="24" :sm="12"><a-form-item :label="`${label}名称`" name="name">
          <a-input v-model:value="draft.name" :disabled="inherited" />
        </a-form-item></a-col>
        <a-col :xs="24" :sm="12"><a-form-item :label="`${label} ID`" name="id">
          <a-input v-model:value="draft.id" :disabled="inherited" />
        </a-form-item></a-col>

        <template v-if="type === 'functions'">
          <a-col :xs="24" :sm="12"><a-form-item label="异步调用">
            <BooleanSelect v-model:value="draft.async" :true-value="true" :false-value="false" :true-label="$t('Base.Base.640395-12')" :false-label="$t('Base.Base.640395-13')" :disabled="inherited" />
          </a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="输入参数">
            <ObjectParams v-model:value="draft.inputs" type="functions" :level="2" :disabled="inherited">
              <a-button :disabled="inherited">配置输入参数（{{ draft.inputs.length }}）</a-button>
            </ObjectParams>
          </a-form-item></a-col>
        </template>

        <template v-if="type === 'events'">
          <a-col :xs="24" :sm="12"><a-form-item label="事件级别">
            <a-select v-model:value="draft.expands.level" :options="EventLevel" :disabled="inherited" />
          </a-form-item></a-col>
          <a-col :xs="24" :sm="12"><a-form-item label="输出参数" name="valueType">
            <ObjectParams v-model:value="draft.valueType.properties" :level="2" :disabled="inherited">
              <a-button :disabled="inherited">配置输出参数（{{ draft.valueType.properties?.length || 0 }}）</a-button>
            </ObjectParams>
          </a-form-item></a-col>
        </template>

        <a-col v-if="type === 'functions' || type === 'tags'" :xs="24" :sm="12">
          <a-form-item :label="type === 'functions' ? '输出参数' : '数据类型'" :name="type === 'functions' ? 'output' : 'valueType'">
            <div class="type-control">
              <TypeSelect v-model:value="valueObject" :disabled="inherited" @value-change="typeChange" />
              <IntegerParams v-if="['int', 'long'].includes(valueObject.type)" v-model:value="valueObject.unit" :disabled="inherited" />
              <DoubleParams v-else-if="['float', 'double'].includes(valueObject.type)" v-model:value="valueObject" :disabled="inherited" />
              <StringParams v-else-if="['string', 'password'].includes(valueObject.type) && type === 'functions'" v-model:value="outputStringValue" :disabled="inherited" />
              <StringParams v-else-if="['string', 'password'].includes(valueObject.type)" v-model:value="valueObject" :disabled="inherited" />
              <DateParams v-else-if="valueObject.type === 'date'" v-model:value="valueObject.format" :disabled="inherited" />
              <FileParams v-else-if="valueObject.type === 'file'" v-model:value="valueObject" :disabled="inherited" />
              <EnumParams v-else-if="valueObject.type === 'enum'" v-model:value="valueObject.elements" :disabled="inherited" />
              <BooleanParams v-else-if="valueObject.type === 'boolean'" v-model:falseText="valueObject.falseText" v-model:falseValue="valueObject.falseValue" v-model:trueText="valueObject.trueText" v-model:trueValue="valueObject.trueValue" :disabled="inherited" />
              <ObjectParams v-else-if="valueObject.type === 'object'" v-model:value="valueObject.properties" :level="2" :disabled="inherited" />
              <ArrayParams v-else-if="valueObject.type === 'array'" v-model:value="valueObject.elementType" :level="2" :disabled="inherited" />
            </div>
          </a-form-item>
        </a-col>
        <a-col :xs="24"><a-form-item label="说明" name="description">
          <a-input v-model:value="draft.description" :disabled="inherited" />
        </a-form-item></a-col>
      </a-row>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, inject, provide, ref, watch } from 'vue'
import { omit } from 'lodash-es'
import type { FormInstance } from 'ant-design-vue'
import { TypeSelect, IntegerParams, DoubleParams, StringParams, DateParams, FileParams, EnumParams, BooleanParams, ObjectParams, ArrayParams, BooleanSelect } from '../../../../../components/Metadata'
import { FULL_SCREEN, TABLE_WRAPPER } from '../../../../../components/Metadata/consts'
import { EventLevel } from '../../../data'
import { validatorConfig } from './columns'
import { getMetadataItemByType } from './utils'

type DefinitionType = 'functions' | 'events' | 'tags'
const props = withDefaults(defineProps<{ open: boolean, record?: Record<string, any>, type: DefinitionType, target: 'product' | 'device', existingIds?: string[] }>(), { existingIds: () => [] })
const emit = defineEmits<{ (event: 'update:open', value: boolean): void, (event: 'save', value: Record<string, any>): void }>()
const formRef = ref<FormInstance>()
const draft = ref<Record<string, any>>()
const label = computed(() => ({ functions: '功能', events: '事件', tags: '标签' })[props.type])
const title = computed(() => `${props.record?.id ? '编辑' : '新增'}${label.value}`)
const inherited = computed(() => !!draft.value?.expands?.isProduct)
const valueObject = computed({
  get: () => props.type === 'functions' ? draft.value!.output : draft.value!.valueType,
  set: (value) => { if (draft.value) draft.value[props.type === 'functions' ? 'output' : 'valueType'] = value },
})
const outputStringValue = computed({
  get: () => {
    const output = draft.value!.output
    const legacy = output.maxLength
    const maxLength = legacy && typeof legacy === 'object'
      ? legacy.maxLength ?? legacy.expands?.maxLength
      : legacy
    return { ...output, maxLength }
  },
  set: (value) => {
    if (!draft.value) return
    const output = draft.value.output
    const maxLength = value.expands?.maxLength
    const next = { ...output, expands: { ...output.expands, ...value.expands } }
    if (Object.prototype.hasOwnProperty.call(output, 'maxLength')) next.maxLength = maxLength
    draft.value.output = next
  },
})

// Keep the modal and its nested widgets inside the table in fullscreen mode.
const tableWrapper = inject(TABLE_WRAPPER, computed(() => document.body))
const getContainer = () => tableWrapper.value || document.body
provide(TABLE_WRAPPER, tableWrapper)
provide(FULL_SCREEN, ref(true))

watch(() => props.open, open => {
  if (!open) return
  const copy = JSON.parse(JSON.stringify(props.record || getMetadataItemByType(props.type)))
  copy.expands = copy.expands || {}
  if (props.type === 'functions') {
    copy.inputs = Array.isArray(copy.inputs) ? copy.inputs : []
    copy.output = copy.output || {}
    if (copy.async === undefined) copy.async = false
  } else {
    copy.valueType = copy.valueType || {}
    if (props.type === 'events') {
      if (!Array.isArray(copy.valueType.properties)) copy.valueType.properties = []
      if (!copy.expands.level) copy.expands.level = 'ordinary'
    }
  }
  draft.value = copy
}, { immediate: true })

const rules = computed(() => ({
  name: [{ required: true, message: `请输入${label.value}名称` }, { max: 64, message: '名称不能超过 64 个字符' }],
  id: [{ required: true, message: `请输入${label.value} ID` }, { max: 64, message: 'ID 不能超过 64 个字符' }, { pattern: /^[a-zA-Z0-9_]+$/, message: 'ID 只能包含字母、数字和下划线' }, { validator: async (_: any, value: string) => { if (value && props.existingIds.includes(value)) throw new Error(`${label.value} ID 已存在`) } }],
  description: [{ max: 20, message: '说明不能超过 20 个字符' }],
  valueType: [{ validator: async (_: any, value: any) => {
    if (props.type === 'events') {
      if (!value?.properties?.length) throw new Error('请配置输出参数')
    } else if (props.type === 'tags') {
      if (!value?.type) throw new Error('请选择数据类型')
      await validatorConfig(value, true)
    }
  } }],
  output: [{ validator: async (_: any, value: any) => { if (props.type === 'functions') await validatorConfig(value) } }],
}))

function typeChange() {
  if (props.type === 'tags' && draft.value) draft.value.expands = omit(draft.value.expands, ['metrics', 'expands'])
}
function cancel() { emit('update:open', false) }
async function submit() {
  if (inherited.value) return
  try {
    await formRef.value?.validate()
    if (draft.value) {
      emit('save', JSON.parse(JSON.stringify(draft.value)))
      emit('update:open', false)
    }
  } catch { /* The form displays validation errors. */ }
}
</script>

<style scoped>
.definition-editor { max-height: 65vh; overflow-y: auto; padding: 4px 4px 0; }
.type-control { display: flex; gap: 8px; align-items: center; }
.type-control > :first-child { flex: 1; min-width: 0; }
</style>
