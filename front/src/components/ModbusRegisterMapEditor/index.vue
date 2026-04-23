<template>
  <div class="modbus-register-map-editor">
    <a-table
      :dataSource="rows"
      :columns="columns"
      :pagination="false"
      size="small"
      bordered
      :scroll="{ x: 900 }"
    >
      <template #bodyCell="{ column, record, index }">
        <template v-if="column.key === 'propertyId'">
          <a-input
            v-model:value="record.propertyId"
            size="small"
            placeholder="属性ID"
            @change="emitChange"
          />
        </template>

        <template v-else-if="column.key === 'functionCode'">
          <a-select
            v-model:value="record.functionCode"
            size="small"
            style="width: 100%"
            @change="emitChange"
          >
            <a-select-option :value="1">0x01 线圈(读)</a-select-option>
            <a-select-option :value="2">0x02 离散输入(读)</a-select-option>
            <a-select-option :value="3">0x03 保持寄存器(读)</a-select-option>
            <a-select-option :value="4">0x04 输入寄存器(读)</a-select-option>
            <a-select-option :value="5">0x05 单线圈(写)</a-select-option>
            <a-select-option :value="6">0x06 单寄存器(写)</a-select-option>
            <a-select-option :value="15">0x0F 多线圈(写)</a-select-option>
            <a-select-option :value="16">0x10 多寄存器(写)</a-select-option>
          </a-select>
        </template>

        <template v-else-if="column.key === 'address'">
          <a-input-number
            v-model:value="record.address"
            size="small"
            :min="0"
            :max="65535"
            style="width: 100%"
            @change="emitChange"
          />
        </template>

        <template v-else-if="column.key === 'quantity'">
          <a-input-number
            v-model:value="record.quantity"
            size="small"
            :min="1"
            :max="125"
            style="width: 100%"
            @change="emitChange"
          />
        </template>

        <template v-else-if="column.key === 'dataType'">
          <a-select
            v-model:value="record.dataType"
            size="small"
            style="width: 100%"
            @change="emitChange"
          >
            <a-select-option value="BIT">BIT</a-select-option>
            <a-select-option value="INT16">INT16</a-select-option>
            <a-select-option value="UINT16">UINT16</a-select-option>
            <a-select-option value="INT32">INT32</a-select-option>
            <a-select-option value="UINT32">UINT32</a-select-option>
            <a-select-option value="FLOAT32">FLOAT32</a-select-option>
            <a-select-option value="INT64">INT64</a-select-option>
            <a-select-option value="FLOAT64">FLOAT64</a-select-option>
          </a-select>
        </template>

        <template v-else-if="column.key === 'byteOrder'">
          <a-select
            v-model:value="record.byteOrder"
            size="small"
            style="width: 100%"
            @change="emitChange"
          >
            <a-select-option value="ABCD">ABCD (大端)</a-select-option>
            <a-select-option value="CDAB">CDAB (字节交换)</a-select-option>
            <a-select-option value="BADC">BADC (字交换)</a-select-option>
            <a-select-option value="DCBA">DCBA (小端)</a-select-option>
          </a-select>
        </template>

        <template v-else-if="column.key === 'scale'">
          <a-input-number
            v-model:value="record.scale"
            size="small"
            :precision="6"
            style="width: 100%"
            @change="emitChange"
          />
        </template>

        <template v-else-if="column.key === 'offset'">
          <a-input-number
            v-model:value="record.offset"
            size="small"
            :precision="6"
            style="width: 100%"
            @change="emitChange"
          />
        </template>

        <template v-else-if="column.key === 'writable'">
          <a-checkbox
            v-model:checked="record.writable"
            @change="emitChange"
          />
        </template>

        <template v-else-if="column.key === 'action'">
          <a-button
            type="link"
            danger
            size="small"
            @click="removeRow(index)"
          >删除</a-button>
        </template>
      </template>
    </a-table>

    <a-button
      type="dashed"
      style="margin-top: 8px; width: 100%"
      @click="addRow"
    >
      + 添加寄存器映射
    </a-button>
  </div>
</template>

<script lang="ts" setup>
import { ref, watch } from 'vue'

interface RegisterMapping {
  propertyId: string
  functionCode: number
  address: number
  quantity: number
  dataType: string
  byteOrder: string
  scale: number
  offset: number
  writable: boolean
}

const props = defineProps<{
  modelValue?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
}>()

const columns = [
  { title: '属性ID', key: 'propertyId', width: 120 },
  { title: '功能码', key: 'functionCode', width: 170 },
  { title: '起始地址', key: 'address', width: 90 },
  { title: '数量', key: 'quantity', width: 70 },
  { title: '数据类型', key: 'dataType', width: 110 },
  { title: '字节序', key: 'byteOrder', width: 130 },
  { title: '比例系数', key: 'scale', width: 90 },
  { title: '偏移量', key: 'offset', width: 90 },
  { title: '可写', key: 'writable', width: 60 },
  { title: '操作', key: 'action', width: 60 },
]

function parseValue(val?: string): RegisterMapping[] {
  if (!val) return []
  try {
    const parsed = JSON.parse(val)
    if (!Array.isArray(parsed)) return []
    return parsed.map((item: any) => ({
      propertyId: item.propertyId ?? item.property ?? '',
      functionCode: item.functionCode ?? item.fc ?? 3,
      address: item.address ?? item.addr ?? 0,
      quantity: item.quantity ?? item.qty ?? 1,
      dataType: item.dataType ?? 'INT16',
      byteOrder: item.byteOrder ?? 'ABCD',
      scale: item.scale ?? 1,
      offset: item.offset ?? 0,
      writable: item.writable ?? false,
    }))
  } catch {
    return []
  }
}

const rows = ref<RegisterMapping[]>(parseValue(props.modelValue))

watch(
  () => props.modelValue,
  (val) => {
    const parsed = parseValue(val)
    if (JSON.stringify(parsed) !== JSON.stringify(rows.value)) {
      rows.value = parsed
    }
  }
)

function emitChange() {
  emit('update:modelValue', JSON.stringify(rows.value))
}

function addRow() {
  rows.value.push({
    propertyId: '',
    functionCode: 3,
    address: 0,
    quantity: 1,
    dataType: 'INT16',
    byteOrder: 'ABCD',
    scale: 1,
    offset: 0,
    writable: false,
  })
  emitChange()
}

function removeRow(index: number) {
  rows.value.splice(index, 1)
  emitChange()
}
</script>

<style scoped>
.modbus-register-map-editor :deep(.ant-table-cell) {
  padding: 4px 6px !important;
}
</style>
