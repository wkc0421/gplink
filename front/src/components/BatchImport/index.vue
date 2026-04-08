<template>
  <a-modal
    :visible="true"
    title="批量导入"
    :width="width"
    :confirm-loading="loading"
    :ok-text="step === 0 ? '下一步' : '确认导入'"
    @ok="handleOk"
    @cancel="handleClose"
  >
    <!-- Step 0: download template + upload -->
    <template v-if="step === 0">
      <a-space direction="vertical" style="width: 100%">
        <!-- Extra slot content (e.g. toggles) -->
        <slot name="content" />

        <!-- Hint message -->
        <a-alert
          v-if="message"
          type="info"
          show-icon
          style="margin-bottom: 8px"
        >
          <template #message>
            <span v-html="message" />
          </template>
        </a-alert>

        <!-- Download template -->
        <div>
          <a-button type="link" :loading="downloading" @click="downloadTemplate">
            <AIcon type="DownloadOutlined" />
            下载导入模板
          </a-button>
        </div>

        <!-- File upload -->
        <a-upload-dragger
          :file-list="fileList"
          :before-upload="beforeUpload"
          :max-count="1"
          accept=".xlsx,.xls,.csv"
          @remove="fileList = []"
        >
          <p class="ant-upload-drag-icon">
            <AIcon type="InboxOutlined" style="font-size: 48px; color: #40a9ff" />
          </p>
          <p class="ant-upload-text">点击或拖拽文件到此区域上传</p>
          <p class="ant-upload-hint">支持 .xlsx / .xls / .csv 格式</p>
        </a-upload-dragger>
      </a-space>
    </template>

    <!-- Step 1: uploading / result -->
    <template v-else>
      <a-result
        v-if="importResult === 'success'"
        status="success"
        title="导入成功"
      />
      <a-result
        v-else-if="importResult === 'error'"
        status="error"
        title="导入失败"
        :sub-title="errorMessage"
      />
      <div v-else style="text-align: center; padding: 32px 0">
        <a-spin tip="正在导入，请稍候..." />
      </div>
    </template>
  </a-modal>
</template>

<script setup lang="ts">
import { onlyMessage } from '@jetlinks-web/utils'
import { fileUpload } from '@/api/comm'

interface Props {
  width?: number | string
  downloadUrlBuilder?: () => Promise<any>
  request?: (fileUrl: string) => Promise<any>
  message?: string
}

const props = withDefaults(defineProps<Props>(), {
  width: 680,
})

const emits = defineEmits(['close', 'save'])

const step = ref(0)
const loading = ref(false)
const downloading = ref(false)
const fileList = ref<any[]>([])
const importResult = ref<'success' | 'error' | 'loading' | null>(null)
const errorMessage = ref('')

const downloadTemplate = async () => {
  if (!props.downloadUrlBuilder) return
  downloading.value = true
  try {
    const resp = await props.downloadUrlBuilder()
    const url = typeof resp === 'string' ? resp : resp?.result || resp?.url || resp
    if (url) {
      const a = document.createElement('a')
      a.href = url
      a.download = ''
      a.click()
    }
  } catch {
    onlyMessage('下载模板失败', 'error')
  } finally {
    downloading.value = false
  }
}

const beforeUpload = (file: File) => {
  fileList.value = [file]
  return false // prevent auto upload
}

const handleOk = async () => {
  if (step.value === 0) {
    if (!fileList.value.length) {
      onlyMessage('请先选择要导入的文件', 'warning')
      return
    }
    step.value = 1
    importResult.value = 'loading'
    loading.value = true
    try {
      // Upload file first
      const formData = new FormData()
      formData.append('file', fileList.value[0])
      const uploadResp = await fileUpload(formData)
      const fileUrl = uploadResp?.result?.accessUrl || uploadResp?.result || uploadResp

      // Call the import request
      if (props.request) {
        await props.request(fileUrl)
      }

      importResult.value = 'success'
    } catch (e: any) {
      importResult.value = 'error'
      errorMessage.value = e?.response?.data?.message || e?.message || '导入失败，请检查文件格式'
    } finally {
      loading.value = false
    }
  } else {
    // Confirm after showing result
    if (importResult.value === 'success') {
      emits('save')
    }
    handleClose()
  }
}

const handleClose = () => {
  emits('close')
}
</script>
