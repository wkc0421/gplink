<template>
  <a-modal
    :visible="visible"
    :title="formState.id ? '编辑定时任务' : '新增定时任务'"
    width="640px"
    :confirm-loading="loading"
    @ok="handleSubmit"
    @cancel="handleCancel"
  >
    <a-form
      ref="formRef"
      :model="formState"
      layout="vertical"
      :rules="rules"
    >
      <a-form-item label="任务名称" name="name">
        <a-input v-model:value="formState.name" placeholder="请输入任务名称" :maxlength="256" />
      </a-form-item>

      <a-form-item label="产品" name="productId">
        <a-select
          v-model:value="formState.productId"
          placeholder="请选择产品"
          show-search
          :filter-option="false"
          :options="productOptions"
          @search="onProductSearch"
          @change="onProductChange"
        />
      </a-form-item>

      <a-form-item label="设备" name="deviceIds">
        <a-select
          v-model:value="formState.deviceIds"
          mode="multiple"
          placeholder="不选择则默认该产品下全部设备"
          :options="deviceOptions"
          :disabled="!formState.productId"
          allow-clear
        />
      </a-form-item>

      <a-form-item label="任务类型" name="taskType">
        <a-radio-group v-model:value="formState.taskType" @change="onTaskTypeChange">
          <a-radio value="READ_PROPERTY">读取属性</a-radio>
          <a-radio value="INVOKE_FUNCTION">调用功能</a-radio>
        </a-radio-group>
      </a-form-item>

      <a-form-item
        v-if="formState.taskType === 'READ_PROPERTY'"
        label="采集项/属性"
        name="properties"
      >
        <a-select
          v-model:value="formState.properties"
          mode="multiple"
          placeholder="请选择要读取的属性"
          :options="propertyOptions"
          :disabled="!formState.productId"
        />
      </a-form-item>

      <template v-if="formState.taskType === 'INVOKE_FUNCTION'">
        <a-form-item label="功能" name="functionId">
          <a-select
            v-model:value="formState.functionId"
            placeholder="请选择要调用的功能"
            :options="functionOptions"
            :disabled="!formState.productId"
            @change="onFunctionChange"
          />
        </a-form-item>

        <template v-if="selectedFunctionInputs.length > 0">
          <a-form-item
            v-for="param in selectedFunctionInputs"
            :key="param.id"
            :label="`${param.name}${param.description ? '(' + param.description + ')' : ''}`"
          >
            <a-input
              v-if="!param.valueType || param.valueType.type === 'string' || param.valueType.type === 'enum'"
              :value="formState.functionParams?.[param.id]"
              :placeholder="`请输入${param.name}`"
              @change="(e: any) => setFunctionParam(param.id, e.target.value)"
            />
            <a-input-number
              v-else-if="param.valueType?.type === 'int' || param.valueType?.type === 'long' || param.valueType?.type === 'float' || param.valueType?.type === 'double'"
              :value="formState.functionParams?.[param.id]"
              style="width: 100%"
              :placeholder="`请输入${param.name}`"
              @change="(val: any) => setFunctionParam(param.id, val)"
            />
            <a-switch
              v-else-if="param.valueType?.type === 'boolean'"
              :checked="formState.functionParams?.[param.id]"
              @change="(val: any) => setFunctionParam(param.id, val)"
            />
            <a-input
              v-else
              :value="typeof formState.functionParams?.[param.id] === 'object' ? JSON.stringify(formState.functionParams?.[param.id]) : formState.functionParams?.[param.id]"
              :placeholder="`请输入${param.name}(JSON格式)`"
              @change="(e: any) => { try { setFunctionParam(param.id, JSON.parse(e.target.value)) } catch { setFunctionParam(param.id, e.target.value) } }"
            />
          </a-form-item>
        </template>
      </template>

      <a-form-item label="定时规则(Cron表达式)" name="cron">
        <a-input
          v-model:value="formState.cron"
          placeholder="例如: 0 */5 * * * ? (每5分钟)"
        />
        <div style="color: #999; font-size: 12px; margin-top: 4px">
          支持标准 Cron 表达式，例如 `0 0/1 * * * ?` 表示每分钟执行一次
        </div>
      </a-form-item>

      <a-form-item label="执行模式" name="executionMode">
        <a-radio-group v-model:value="formState.executionMode">
          <a-radio value="PARALLEL">并行(所有设备同时执行)</a-radio>
          <a-radio value="SERIAL">串行(逐台设备顺序执行)</a-radio>
        </a-radio-group>
      </a-form-item>

      <a-form-item
        v-if="formState.executionMode === 'SERIAL'"
        label="设备间执行间隔(毫秒)"
        name="serialIntervalMs"
      >
        <a-input-number
          v-model:value="formState.serialIntervalMs"
          :min="0"
          :step="100"
          style="width: 100%"
          placeholder="默认0ms"
        />
      </a-form-item>

      <a-form-item
        v-if="formState.taskType === 'READ_PROPERTY'"
        label="属性读取间隔(毫秒)"
        name="propertyIntervalMs"
      >
        <a-input-number
          v-model:value="formState.propertyIntervalMs"
          :min="0"
          :step="100"
          style="width: 100%"
          placeholder="默认500ms"
        />
      </a-form-item>
    </a-form>
  </a-modal>
</template>

<script setup lang="ts">
import type { FormInstance } from 'ant-design-vue'
import { onlyMessage } from '@jetlinks-web/utils'
import {
  saveScheduledTask,
  getProductListNoPaging,
  getProductMetadata,
  getDevicesByProduct,
} from '@/api/device/scheduledTask'

interface Props {
  visible: boolean
  record?: any
}

const props = withDefaults(defineProps<Props>(), {
  record: null,
})

const emits = defineEmits(['update:visible', 'saved'])

const formRef = ref<FormInstance>()
const loading = ref(false)

const productOptions = ref<any[]>([])
const deviceOptions = ref<any[]>([])
const propertyOptions = ref<any[]>([])
const functionOptions = ref<any[]>([])
const selectedFunctionInputs = ref<any[]>([])

const formState = ref<any>({
  id: undefined,
  name: '',
  productId: undefined,
  productName: '',
  deviceIds: [],
  taskType: 'READ_PROPERTY',
  properties: [],
  functionId: undefined,
  functionParams: {},
  cron: '',
  executionMode: 'PARALLEL',
  serialIntervalMs: 0,
  propertyIntervalMs: 500,
})

const rules = {
  name: [{ required: true, message: '请输入任务名称' }],
  productId: [{ required: true, message: '请选择产品' }],
  taskType: [{ required: true, message: '请选择任务类型' }],
  cron: [{ required: true, message: '请输入Cron表达式' }],
  executionMode: [{ required: true, message: '请选择执行模式' }],
}

const loadProducts = async (keyword?: string) => {
  const resp = await getProductListNoPaging(
    keyword ? { terms: [{ column: 'name', termType: 'like$', value: keyword }] } : {},
  )
  if (resp.result) {
    productOptions.value = resp.result.map((p: any) => ({
      label: p.name,
      value: p.id,
      name: p.name,
    }))
  }
}

const loadProductOptions = async (productId: string) => {
  const [devResp, metaResp] = await Promise.all([
    getDevicesByProduct(productId),
    getProductMetadata(productId),
  ])

  deviceOptions.value = (devResp.result || []).map((d: any) => ({
    label: d.name,
    value: d.id,
  }))

  const meta = metaResp.result || metaResp
  propertyOptions.value = (meta?.properties || []).map((p: any) => ({
    label: `${p.name}(${p.id})`,
    value: p.id,
  }))
  functionOptions.value = (meta?.functions || []).map((f: any) => ({
    label: `${f.name}(${f.id})`,
    value: f.id,
    inputs: f.inputs || [],
  }))
}

const onProductSearch = (val: string) => {
  loadProducts(val)
}

const onProductChange = async (val: string) => {
  const opt = productOptions.value.find((p) => p.value === val)
  formState.value.productName = opt?.name || ''
  formState.value.deviceIds = []
  formState.value.properties = []
  formState.value.functionId = undefined
  formState.value.functionParams = {}
  selectedFunctionInputs.value = []

  if (val) {
    await loadProductOptions(val)
  } else {
    deviceOptions.value = []
    propertyOptions.value = []
    functionOptions.value = []
  }
}

const onTaskTypeChange = () => {
  formState.value.properties = []
  formState.value.functionId = undefined
  formState.value.functionParams = {}
  selectedFunctionInputs.value = []
}

const onFunctionChange = (val: string) => {
  formState.value.functionParams = {}
  const opt = functionOptions.value.find((f) => f.value === val)
  selectedFunctionInputs.value = opt?.inputs || []
}

const setFunctionParam = (key: string, value: any) => {
  formState.value.functionParams = {
    ...formState.value.functionParams,
    [key]: value,
  }
}

const handleSubmit = async () => {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }

  loading.value = true
  try {
    const payload = {
      ...formState.value,
      timerSpec: {
        trigger: 'cron',
        cron: formState.value.cron,
      },
    }
    await saveScheduledTask(payload)
    onlyMessage('保存成功')
    emits('saved')
    emits('update:visible', false)
  } catch (e) {
    // error handled by request interceptor
  } finally {
    loading.value = false
  }
}

const handleCancel = () => {
  emits('update:visible', false)
}

onMounted(async () => {
  await loadProducts()

  if (props.record) {
    const rec = props.record
    formState.value = {
      id: rec.id,
      name: rec.name,
      productId: rec.productId,
      productName: rec.productName,
      deviceIds: rec.deviceIds || [],
      taskType: rec.taskType || 'READ_PROPERTY',
      properties: rec.properties || [],
      functionId: rec.functionId,
      functionParams: rec.functionParams || {},
      cron: rec.timerSpec?.cron || '',
      executionMode: rec.executionMode || 'PARALLEL',
      serialIntervalMs: rec.serialIntervalMs ?? 0,
      propertyIntervalMs: rec.propertyIntervalMs ?? 500,
    }

    if (rec.productId) {
      await loadProductOptions(rec.productId)
      if (rec.functionId) {
        onFunctionChange(rec.functionId)
      }
    }
  }
})
</script>
