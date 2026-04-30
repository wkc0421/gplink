<template>
  <a-modal
    :visible="visible"
    :title="`执行日志 - ${taskName}`"
    width="900px"
    :footer="null"
    @cancel="handleCancel"
  >
    <div style="margin-bottom: 12px; text-align: right">
      <a-popconfirm
        title="确认删除该任务的全部执行日志？"
        @confirm="clearLogs"
      >
        <j-permission-button danger hasPermission="device/scheduledTask:delete">
          批量删除日志
        </j-permission-button>
      </a-popconfirm>
    </div>

    <j-pro-table
      ref="logsTableRef"
      :columns="logColumns"
      :request="fetchLogs"
      mode="TABLE"
      :bodyStyle="{ padding: 0 }"
      :scroll="{ y: 480 }"
    >
      <template #status="slotProps">
        <j-badge-status
          :status="slotProps.status"
          :text="slotProps.status === 'SUCCESS' ? '成功' : '失败'"
          :statusNames="{ SUCCESS: 'success', FAILED: 'error' }"
        />
      </template>

      <template #detail="slotProps">
        <a-tooltip placement="topLeft">
          <template #title>
            <span>{{ formatDetail(slotProps.detail) }}</span>
          </template>
          <span style="cursor: pointer">{{ truncate(formatDetail(slotProps.detail), 60) }}</span>
        </a-tooltip>
      </template>

      <template #createTime="slotProps">
        {{ slotProps.createTime ? dayjs(slotProps.createTime).format('YYYY-MM-DD HH:mm:ss') : '-' }}
      </template>
    </j-pro-table>
  </a-modal>
</template>

<script setup lang="ts">
import dayjs from 'dayjs'
import { onlyMessage } from '@jetlinks-web/utils'
import { queryLogs, deleteLogs } from '@/api/device/scheduledTask'

interface Props {
  visible: boolean
  taskId?: string
  taskName?: string
}

const props = withDefaults(defineProps<Props>(), {
  taskName: '',
})

const emits = defineEmits(['update:visible'])

const logsTableRef = ref<any>()

const logColumns = [
  {
    title: '设备ID',
    dataIndex: 'deviceId',
    key: 'deviceId',
    ellipsis: true,
    width: '180px',
  },
  {
    title: '设备名称',
    dataIndex: 'deviceName',
    key: 'deviceName',
    ellipsis: true,
    width: '150px',
  },
  {
    title: '执行结果',
    dataIndex: 'status',
    key: 'status',
    scopedSlots: true,
    width: '100px',
  },
  {
    title: '详情',
    dataIndex: 'detail',
    key: 'detail',
    scopedSlots: true,
    ellipsis: true,
  },
  {
    title: '执行时间',
    dataIndex: 'createTime',
    key: 'createTime',
    scopedSlots: true,
    width: '180px',
  },
]

const fetchLogs = (params: any) => {
  if (!props.taskId) {
    return Promise.resolve({ result: { data: [], pageIndex: 0, total: 0 } })
  }
  return queryLogs(props.taskId, params)
}

const clearLogs = async () => {
  if (!props.taskId) return
  await deleteLogs(props.taskId)
  onlyMessage('日志已清空')
  logsTableRef.value?.reload()
}

const formatDetail = (detail: any) => {
  if (detail === null || detail === undefined) return '-'
  if (typeof detail === 'string') return detail
  return JSON.stringify(detail)
}

const truncate = (str: string, len: number) => {
  if (!str) return '-'
  return str.length > len ? str.slice(0, len) + '...' : str
}

const handleCancel = () => {
  emits('update:visible', false)
}
</script>
