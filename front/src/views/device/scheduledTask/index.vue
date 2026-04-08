<template>
  <j-page-container>
    <pro-search
      :columns="columns"
      target="scheduled-task"
      @search="(e: any) => (queryParams = e)"
    />
    <j-pro-table
      ref="tableRef"
      :columns="columns"
      :request="queryScheduledTasks"
      mode="TABLE"
      :params="queryParams"
      :defaultParams="{ sorts: [{ name: 'createTime', order: 'desc' }] }"
    >
      <template #headerRightRender>
        <j-permission-button
          type="primary"
          hasPermission="scheduled-task:add"
          @click="openModal(null)"
        >
          新增任务
        </j-permission-button>
      </template>

      <template #taskType="slotProps">
        <span>{{ taskTypeLabel(slotProps.taskType) }}</span>
      </template>

      <template #state="slotProps">
        <j-badge-status
          :status="slotProps.state"
          :text="slotProps.state === 'enabled' ? '已启用' : '已禁用'"
          :statusNames="{ enabled: 'success', disabled: 'error' }"
        />
      </template>

      <template #logEnabled="slotProps">
        <a-switch
          :checked="slotProps.logEnabled"
          checked-children="开"
          un-checked-children="关"
          @change="(checked: boolean) => toggleLog(slotProps, checked)"
        />
      </template>

      <template #createTime="slotProps">
        {{ slotProps.createTime ? dayjs(slotProps.createTime).format('YYYY-MM-DD HH:mm:ss') : '-' }}
      </template>

      <template #action="slotProps">
        <a-space :size="16">
          <j-permission-button
            type="link"
            hasPermission="scheduled-task:update"
            :tooltip="{ title: '编辑' }"
            @click="openModal(slotProps)"
          >
            <AIcon type="EditOutlined" />
          </j-permission-button>

          <j-permission-button
            type="link"
            hasPermission="scheduled-task:save"
            :tooltip="{ title: slotProps.state === 'enabled' ? '禁用' : '启用' }"
            :popConfirm="{
              title: slotProps.state === 'enabled' ? '确认禁用该任务?' : '确认启用该任务?',
              onConfirm: () => toggleState(slotProps),
            }"
          >
            <AIcon :type="slotProps.state === 'enabled' ? 'StopOutlined' : 'PlayCircleOutlined'" />
          </j-permission-button>

          <j-permission-button
            type="link"
            hasPermission="scheduled-task:query"
            :tooltip="{ title: '执行日志' }"
            @click="openLogs(slotProps)"
          >
            <AIcon type="FileTextOutlined" />
          </j-permission-button>

          <j-permission-button
            type="link"
            hasPermission="scheduled-task:delete"
            :tooltip="{ title: '删除' }"
            :popConfirm="{
              title: '确认删除该任务?',
              onConfirm: () => remove(slotProps.id),
            }"
          >
            <AIcon type="DeleteOutlined" />
          </j-permission-button>
        </a-space>
      </template>
    </j-pro-table>

    <TaskFormModal
      v-if="formVisible"
      v-model:visible="formVisible"
      :record="currentRecord"
      @saved="tableRef?.reload()"
    />

    <TaskLogsModal
      v-if="logsVisible"
      v-model:visible="logsVisible"
      :task-id="currentRecord?.id"
      :task-name="currentRecord?.name"
    />
  </j-page-container>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import { onlyMessage } from '@jetlinks-web/utils'
import {
  queryScheduledTasks,
  deleteScheduledTask,
  enableTask,
  disableTask,
  enableLog,
  disableLog,
} from '@/api/device/scheduledTask'
import TaskFormModal from './components/TaskFormModal.vue'
import TaskLogsModal from './components/TaskLogsModal.vue'

const tableRef = ref<any>()
const queryParams = ref<any>({})
const formVisible = ref(false)
const logsVisible = ref(false)
const currentRecord = ref<any>(null)

const columns = [
  {
    title: '任务名称',
    dataIndex: 'name',
    key: 'name',
    search: { type: 'string' },
    ellipsis: true,
  },
  {
    title: '产品',
    dataIndex: 'productName',
    key: 'productName',
    ellipsis: true,
  },
  {
    title: '任务类型',
    dataIndex: 'taskType',
    key: 'taskType',
    search: {
      type: 'select',
      termFilter: ['in', 'nin'],
      options: [
        { label: '读取属性', value: 'READ_PROPERTY' },
        { label: '调用功能', value: 'INVOKE_FUNCTION' },
      ],
    },
    scopedSlots: true,
    ellipsis: true,
  },
  {
    title: '执行状态',
    dataIndex: 'state',
    key: 'state',
    search: {
      type: 'select',
      termFilter: ['in', 'nin'],
      options: [
        { label: '已启用', value: 'enabled' },
        { label: '已禁用', value: 'disabled' },
      ],
    },
    scopedSlots: true,
    ellipsis: true,
  },
  {
    title: '记录日志',
    dataIndex: 'logEnabled',
    key: 'logEnabled',
    scopedSlots: true,
    width: '100px',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    search: { type: 'date' },
    scopedSlots: true,
    ellipsis: true,
    width: '180px',
  },
  {
    title: '操作',
    dataIndex: 'action',
    key: 'action',
    scopedSlots: true,
    width: '200px',
  },
]

const taskTypeLabel = (type: string) => {
  return type === 'READ_PROPERTY' ? '读取属性' : type === 'INVOKE_FUNCTION' ? '调用功能' : type
}

const openModal = (record: any) => {
  currentRecord.value = record
  formVisible.value = true
}

const openLogs = (record: any) => {
  currentRecord.value = record
  logsVisible.value = true
}

const toggleState = async (record: any) => {
  if (record.state === 'enabled') {
    await disableTask(record.id)
  } else {
    await enableTask(record.id)
  }
  onlyMessage('操作成功')
  tableRef.value?.reload()
}

const toggleLog = async (record: any, checked: boolean) => {
  if (checked) {
    await enableLog(record.id)
  } else {
    await disableLog(record.id)
  }
  onlyMessage('操作成功')
  tableRef.value?.reload()
}

const remove = async (id: string) => {
  await deleteScheduledTask(id)
  onlyMessage('删除成功')
  tableRef.value?.reload()
}
</script>
