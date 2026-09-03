<template>
  <j-page-container>
    <div class="api-token-toolbar">
      <a-button type="primary" @click="openCreate">
        <template #icon><AIcon type="PlusOutlined" /></template>
        创建令牌
      </a-button>
    </div>

    <a-table
      row-key="id"
      :data-source="rows"
      :loading="loading"
      :pagination="pagination"
      @change="onPageChange"
    >
      <a-table-column title="名称" data-index="name" />
      <a-table-column title="状态" key="status" width="100">
        <template #default="{ record }">
          <a-tag :color="statusInfo(record).color">{{ statusInfo(record).text }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="令牌尾号" key="tokenHint" width="130">
        <template #default="{ record }">•••• {{ record.tokenHint || '-' }}</template>
      </a-table-column>
      <a-table-column title="到期时间" key="expiresAt" width="190">
        <template #default="{ record }">{{ formatTime(record.expiresAt) }}</template>
      </a-table-column>
      <a-table-column title="最近使用" key="lastUsedAt" width="190">
        <template #default="{ record }">{{ formatTime(record.lastUsedAt) }}</template>
      </a-table-column>
      <a-table-column title="操作" key="actions" width="190" fixed="right">
        <template #default="{ record }">
          <a-space>
            <a-button
              type="link"
              size="small"
              :disabled="!canRotate(record)"
              @click="openRotate(record)"
            >
              调整/轮换
            </a-button>
            <a-button
              type="link"
              danger
              size="small"
              :disabled="record.status !== 'active'"
              @click="revoke(record)"
            >
              吊销
            </a-button>
          </a-space>
        </template>
      </a-table-column>
    </a-table>

    <a-modal
      v-model:open="editorVisible"
      :title="editingId ? '调整并轮换令牌' : '创建令牌'"
      width="920px"
      :confirm-loading="submitting"
      :mask-closable="false"
      @ok="submitEditor"
    >
      <a-spin :spinning="editorLoading">
        <div class="editor-body">
          <section class="form-section">
            <div class="section-title">基本信息</div>
            <a-form :model="form" layout="vertical">
              <a-row :gutter="16">
                <a-col :span="12">
                  <a-form-item label="名称" required>
                    <a-input v-model:value="form.name" :maxlength="128" placeholder="例如：生产数据查询服务" />
                  </a-form-item>
                </a-col>
                <a-col :span="12">
                  <a-form-item label="说明">
                    <a-input v-model:value="form.description" :maxlength="1024" placeholder="说明使用方和用途" />
                  </a-form-item>
                </a-col>
              </a-row>
            </a-form>
          </section>

          <section class="form-section">
            <div class="section-head">
              <div>
                <div class="section-title">功能权限</div>
                <div class="section-tip">只展示当前账号能够授予的外部 API 权限。</div>
              </div>
              <a-space>
                <a-button size="small" @click="selectAllPermissions">全选</a-button>
                <a-button size="small" @click="clearPermissions">清空</a-button>
              </a-space>
            </div>
            <a-input-search v-model:value="permissionKeyword" allow-clear placeholder="搜索资源或动作" class="permission-search" />

            <a-empty v-if="!filteredResources.length" description="没有可授予的外部 API 权限" />
            <div v-else class="permission-list">
              <div
                v-for="resource in filteredResources"
                :key="resource.id"
                class="permission-row"
                :class="{ 'permission-row--risk': resource.highRisk }"
              >
                <div class="permission-resource">
                  <a-checkbox
                    :checked="isResourceChecked(resource)"
                    :indeterminate="isResourceIndeterminate(resource)"
                    @change="toggleResource(resource, $event.target.checked)"
                  >
                    <span class="resource-name">{{ resource.name }}</span>
                  </a-checkbox>
                  <a-tag v-if="resource.highRisk" color="red">高危</a-tag>
                  <div class="resource-description">{{ resource.description }}</div>
                  <code>{{ resource.id }}</code>
                </div>
                <div class="permission-actions">
                  <a-checkbox
                    v-for="action in resource.actions"
                    :key="action.id"
                    :checked="hasAction(resource.id, action.id)"
                    @change="toggleAction(resource.id, action.id, $event.target.checked)"
                  >
                    <a-tooltip :title="action.description">{{ action.name }}</a-tooltip>
                  </a-checkbox>
                </div>
              </div>
            </div>

            <div v-if="selectedPermissionCount" class="selected-summary">
              <span>已选 {{ selectedPermissionCount }} 项：</span>
              <a-tag v-for="item in selectedPermissionTags" :key="item.key">{{ item.label }}</a-tag>
            </div>
          </section>

          <section class="form-section">
            <div class="section-title">数据范围</div>
            <a-alert
              type="info"
              show-icon
              message="设备允许条件为：设备 ID 命中，或设备所属产品 ID 命中。产品筛选只用于查找设备，不会自动授予产品权限。"
              class="scope-rule"
            />
            <a-row :gutter="16">
              <a-col :span="12">
                <a-form-item label="产品范围">
                  <a-select
                    v-model:value="form.productIds"
                    mode="multiple"
                    show-search
                    allow-clear
                    :filter-option="false"
                    :options="productOptions"
                    :loading="productLoading"
                    placeholder="按名称搜索并选择产品"
                    @search="searchProducts"
                  />
                </a-form-item>
              </a-col>
              <a-col :span="12">
                <a-form-item>
                  <template #label>
                    设备筛选产品
                    <a-tooltip title="只过滤设备候选项，不会加入产品授权范围">
                      <AIcon type="QuestionCircleOutlined" />
                    </a-tooltip>
                  </template>
                  <a-select
                    v-model:value="deviceFilterProductId"
                    show-search
                    allow-clear
                    :filter-option="filterOption"
                    :options="allProductOptions"
                    placeholder="全部产品"
                    @change="loadDevices('')"
                  />
                </a-form-item>
              </a-col>
            </a-row>
            <a-form-item label="设备范围">
              <a-select
                v-model:value="form.deviceIds"
                mode="multiple"
                show-search
                allow-clear
                :filter-option="false"
                :options="deviceOptions"
                :loading="deviceLoading"
                placeholder="按名称搜索并选择设备"
                @search="searchDevices"
              />
            </a-form-item>
            <a-alert
              v-if="!form.productIds.length && !form.deviceIds.length"
              type="warning"
              show-icon
              message="未选择产品或设备范围：该令牌无法访问任何产品和设备数据，空范围不会被解释为全部。"
            />
          </section>

          <section class="form-section form-section--last">
            <div class="section-title">有效期</div>
            <a-space wrap class="expiry-presets">
              <span class="section-tip">快捷选择：</span>
              <a-button v-for="days in expiryPresets" :key="days" size="small" @click="setExpiry(days)">
                {{ days }} 天
              </a-button>
            </a-space>
            <a-form-item label="截止时间" required>
              <a-date-picker
                v-model:value="form.expiresAt"
                show-time
                format="YYYY-MM-DD HH:mm:ss"
                :disabled-date="disabledExpiryDate"
                style="width: 100%"
              />
              <div class="section-tip expiry-tip">
                按当前时区显示，可设置范围为当前时间后 {{ formatDuration(grantOptions.minLifetimeMs) }} 至 {{ formatDuration(grantOptions.maxLifetimeMs) }}。
              </div>
            </a-form-item>
          </section>
        </div>
      </a-spin>
    </a-modal>

    <a-modal v-model:open="secretVisible" title="令牌只显示一次" :footer="null" :mask-closable="false">
      <a-alert type="warning" show-icon message="请立即复制并妥善保存，关闭后只能看到令牌尾号。" />
      <a-input-group compact class="secret-input">
        <a-input v-model:value="secret" readonly />
        <a-button type="primary" @click="copySecret">复制</a-button>
      </a-input-group>
    </a-modal>
  </j-page-container>
</template>

<script setup lang="ts">
import dayjs, { Dayjs } from 'dayjs'
import { computed, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import {
  createApiToken,
  getApiToken,
  getApiTokenGrantOptions,
  queryApiTokenDevices,
  queryApiTokenProducts,
  queryApiTokens,
  revokeApiToken,
  rotateApiToken,
} from '@authentication-manager-ui/api/system/apiToken'

interface ActionOption {
  id: string
  name: string
  description?: string
}

interface ResourceOption {
  id: string
  name: string
  group: string
  description?: string
  highRisk: boolean
  actions: ActionOption[]
}

const DEFAULT_GRANT_OPTIONS = {
  resources: [] as ResourceOption[],
  defaultLifetimeMs: 30 * 24 * 60 * 60 * 1000,
  minLifetimeMs: 5 * 60 * 1000,
  maxLifetimeMs: 365 * 24 * 60 * 60 * 1000,
}

const rows = ref<any[]>([])
const loading = ref(false)
const page = ref(0)
const size = ref(20)
const total = ref(0)
const editorVisible = ref(false)
const editorLoading = ref(false)
const submitting = ref(false)
const editingId = ref<string>()
const secretVisible = ref(false)
const secret = ref('')
const permissionKeyword = ref('')
const selectedPermissions = ref<Record<string, string[]>>({})
const grantOptions = reactive({ ...DEFAULT_GRANT_OPTIONS })
const productLoading = ref(false)
const deviceLoading = ref(false)
const productItems = ref<any[]>([])
const deviceItems = ref<any[]>([])
const productStore = ref<Record<string, any>>({})
const deviceStore = ref<Record<string, any>>({})
const deviceFilterProductId = ref<string>()
const expiryPresets = [7, 30, 90, 180, 365]
let productTimer: ReturnType<typeof setTimeout> | undefined
let deviceTimer: ReturnType<typeof setTimeout> | undefined

const form = reactive<{
  name: string
  description: string
  productIds: string[]
  deviceIds: string[]
  expiresAt: Dayjs | null
}>({
  name: '',
  description: '',
  productIds: [],
  deviceIds: [],
  expiresAt: null,
})

const unwrap = (response: any) => {
  if (response?.result !== undefined) return response.result
  if (response?.data?.result !== undefined) return response.data.result
  if (response?.data !== undefined && (response?.status !== undefined || response?.headers !== undefined)) return response.data
  return response
}
const normalizeList = (response: any) => {
  const value = unwrap(response)
  if (Array.isArray(value)) return value
  return value?.data || []
}

const pagination = computed(() => ({
  current: page.value + 1,
  pageSize: size.value,
  total: total.value,
  showSizeChanger: true,
}))

const filteredResources = computed(() => {
  const keyword = permissionKeyword.value.trim().toLowerCase()
  if (!keyword) return grantOptions.resources
  return grantOptions.resources.filter(resource =>
    [resource.name, resource.group, resource.id, resource.description, ...resource.actions.flatMap(action => [action.name, action.id])]
      .filter(Boolean)
      .some(value => String(value).toLowerCase().includes(keyword)),
  )
})

const selectedPermissionCount = computed(() =>
  Object.values(selectedPermissions.value).reduce((count, actions) => count + actions.length, 0),
)

const selectedPermissionTags = computed(() => grantOptions.resources.flatMap(resource =>
  resource.actions
    .filter(action => hasAction(resource.id, action.id))
    .map(action => ({ key: `${resource.id}:${action.id}`, label: `${resource.name} / ${action.name}` })),
))

const selectedHighRisk = computed(() => grantOptions.resources.some(resource =>
  resource.highRisk && (selectedPermissions.value[resource.id]?.length || 0) > 0,
))

const toProductOption = (item: any) => ({
  label: `${item.name || item.id}（${item.id}）`,
  value: item.id,
})

const toDeviceOption = (item: any) => {
  const product = productStore.value[item.productId]
  const productLabel = product ? `${product.name || product.id}（${product.id}）` : item.productId || '未知产品'
  return {
    label: `${item.name || item.id}（${item.id}）— ${productLabel}`,
    value: item.id,
  }
}

const productOptions = computed(() => {
  const ids = new Set([...productItems.value.map(item => item.id), ...form.productIds])
  return [...ids].map(id => productStore.value[id]).filter(Boolean).map(toProductOption)
})

const allProductOptions = computed(() => Object.values(productStore.value).map(toProductOption))

const deviceOptions = computed(() => {
  const ids = new Set([...deviceItems.value.map(item => item.id), ...form.deviceIds])
  return [...ids].map(id => deviceStore.value[id]).filter(Boolean).map(toDeviceOption)
})

const load = async () => {
  loading.value = true
  try {
    const response = await queryApiTokens({ pageIndex: page.value, pageSize: size.value })
    const result = unwrap(response)
    rows.value = Array.isArray(result) ? result : result?.data || []
    total.value = Array.isArray(result) ? result.length : Number(result?.total || rows.value.length)
  } finally {
    loading.value = false
  }
}

const loadGrantOptions = async () => {
  const result = unwrap(await getApiTokenGrantOptions()) || DEFAULT_GRANT_OPTIONS
  grantOptions.resources = result.resources || []
  grantOptions.defaultLifetimeMs = result.defaultLifetimeMs || DEFAULT_GRANT_OPTIONS.defaultLifetimeMs
  grantOptions.minLifetimeMs = result.minLifetimeMs || DEFAULT_GRANT_OPTIONS.minLifetimeMs
  grantOptions.maxLifetimeMs = result.maxLifetimeMs || DEFAULT_GRANT_OPTIONS.maxLifetimeMs
}

const buildTerms = (keyword: string, productId?: string) => {
  const terms: any[] = []
  if (keyword.trim()) terms.push({ column: 'name', termType: 'like', value: `%${keyword.trim()}%` })
  if (productId) terms.push({ column: 'productId', value: productId })
  return terms
}

const loadProducts = async (keyword = '') => {
  productLoading.value = true
  try {
    const list = normalizeList(await queryApiTokenProducts({
      paging: false,
      sorts: [{ name: 'name', order: 'asc' }],
      terms: buildTerms(keyword),
    }))
    productItems.value = list
    list.forEach((item: any) => { productStore.value[item.id] = item })
  } finally {
    productLoading.value = false
  }
}

const loadDevices = async (keyword = '') => {
  deviceLoading.value = true
  try {
    const list = normalizeList(await queryApiTokenDevices({
      paging: false,
      sorts: [{ name: 'name', order: 'asc' }],
      terms: buildTerms(keyword, deviceFilterProductId.value),
    }))
    deviceItems.value = list
    list.forEach((item: any) => { deviceStore.value[item.id] = item })
  } finally {
    deviceLoading.value = false
  }
}

const searchProducts = (keyword: string) => {
  if (productTimer) clearTimeout(productTimer)
  productTimer = setTimeout(() => loadProducts(keyword), 300)
}

const searchDevices = (keyword: string) => {
  if (deviceTimer) clearTimeout(deviceTimer)
  deviceTimer = setTimeout(() => loadDevices(keyword), 300)
}

const resetForm = () => {
  form.name = ''
  form.description = ''
  form.productIds = []
  form.deviceIds = []
  form.expiresAt = dayjs().add(grantOptions.defaultLifetimeMs, 'millisecond')
  selectedPermissions.value = {}
  permissionKeyword.value = ''
  deviceFilterProductId.value = undefined
  editingId.value = undefined
}

const prepareEditor = async () => {
  editorLoading.value = true
  try {
    await Promise.all([loadGrantOptions(), loadProducts(), loadDevices()])
    if (!editingId.value) form.expiresAt = dayjs().add(grantOptions.defaultLifetimeMs, 'millisecond')
  } finally {
    editorLoading.value = false
  }
}

const openCreate = async () => {
  resetForm()
  editorVisible.value = true
  await prepareEditor()
}

const openRotate = async (record: any) => {
  resetForm()
  editingId.value = record.id
  editorVisible.value = true
  editorLoading.value = true
  try {
    const [detailResponse] = await Promise.all([getApiToken(record.id), loadGrantOptions(), loadProducts(), loadDevices()])
    const detail = unwrap(detailResponse)
    form.name = detail.name || ''
    form.description = detail.description || ''
    form.productIds = [...(detail.productIds || [])]
    form.deviceIds = [...(detail.deviceIds || [])]
    form.expiresAt = detail.expiresAt ? dayjs(detail.expiresAt) : null
    selectedPermissions.value = Object.fromEntries(
      Object.entries(detail.permissions || {}).map(([resource, actions]) => [resource, [...(actions as string[])]]),
    )
  } finally {
    editorLoading.value = false
  }
}

const hasAction = (resource: string, action: string) => selectedPermissions.value[resource]?.includes(action) || false

const toggleAction = (resource: string, action: string, checked: boolean) => {
  const actions = new Set(selectedPermissions.value[resource] || [])
  checked ? actions.add(action) : actions.delete(action)
  const next = { ...selectedPermissions.value }
  if (actions.size) next[resource] = [...actions]
  else delete next[resource]
  selectedPermissions.value = next
}

const toggleResource = (resource: ResourceOption, checked: boolean) => {
  const next = { ...selectedPermissions.value }
  if (checked) next[resource.id] = resource.actions.map(action => action.id)
  else delete next[resource.id]
  selectedPermissions.value = next
}

const isResourceChecked = (resource: ResourceOption) =>
  resource.actions.length > 0 && resource.actions.every(action => hasAction(resource.id, action.id))

const isResourceIndeterminate = (resource: ResourceOption) => {
  const count = resource.actions.filter(action => hasAction(resource.id, action.id)).length
  return count > 0 && count < resource.actions.length
}

const selectAllPermissions = () => {
  selectedPermissions.value = Object.fromEntries(
    grantOptions.resources.map(resource => [resource.id, resource.actions.map(action => action.id)]),
  )
}

const clearPermissions = () => { selectedPermissions.value = {} }

const setExpiry = (days: number) => { form.expiresAt = dayjs().add(days, 'day') }

const disabledExpiryDate = (current: Dayjs) => {
  const min = dayjs().add(grantOptions.minLifetimeMs, 'millisecond')
  const max = dayjs().add(grantOptions.maxLifetimeMs, 'millisecond')
  return current.endOf('day').isBefore(min) || current.startOf('day').isAfter(max)
}

const formatDuration = (milliseconds: number) => {
  if (milliseconds >= 24 * 60 * 60 * 1000) return `${Math.round(milliseconds / 86400000)} 天`
  return `${Math.round(milliseconds / 60000)} 分钟`
}

const validateForm = () => {
  if (!form.name.trim()) return '请输入令牌名称'
  if (!selectedPermissionCount.value) return '请至少选择一项功能权限'
  if (!form.expiresAt) return '请选择截止时间'
  const lifetime = form.expiresAt.valueOf() - Date.now()
  if (lifetime < grantOptions.minLifetimeMs || lifetime > grantOptions.maxLifetimeMs) {
    return `截止时间必须在当前时间后 ${formatDuration(grantOptions.minLifetimeMs)} 至 ${formatDuration(grantOptions.maxLifetimeMs)}之间`
  }
  return undefined
}

const confirmRisk = () => new Promise<boolean>((resolve) => {
  const messages: string[] = []
  if (editingId.value) messages.push('提交后旧令牌将立即失效，新令牌只显示一次。')
  if (selectedHighRisk.value) messages.push('当前配置包含系统高危操作权限，请确认使用方和用途可信。')
  if (!messages.length) return resolve(true)
  Modal.confirm({
    title: editingId.value ? '确认调整并轮换令牌？' : '确认授予高危权限？',
    content: messages.join(' '),
    okText: editingId.value ? '确认轮换' : '确认创建',
    okType: selectedHighRisk.value ? 'danger' : 'primary',
    cancelText: '取消',
    onOk: () => resolve(true),
    onCancel: () => resolve(false),
  })
})

const submitEditor = async () => {
  const error = validateForm()
  if (error) return message.warning(error)
  if (!(await confirmRisk())) return
  submitting.value = true
  try {
    const payload = {
      name: form.name.trim(),
      description: form.description.trim(),
      expiresAt: form.expiresAt!.valueOf(),
      permissions: selectedPermissions.value,
      productIds: [...new Set(form.productIds)],
      deviceIds: [...new Set(form.deviceIds)],
    }
    const response = editingId.value
      ? await rotateApiToken(editingId.value, payload)
      : await createApiToken(payload)
    const result = unwrap(response)
    if (!result?.token) throw new Error('服务端未返回新令牌')
    secret.value = result.token
    editorVisible.value = false
    secretVisible.value = true
    resetForm()
    await load()
  } catch (error: any) {
    message.error(error?.message || '保存令牌失败')
  } finally {
    submitting.value = false
  }
}

const revoke = (record: any) => Modal.confirm({
  title: '确认吊销令牌？',
  content: '吊销后令牌立即失效，且不能恢复或再次轮换。',
  okText: '确认吊销',
  okType: 'danger',
  cancelText: '取消',
  onOk: async () => {
    await revokeApiToken(record.id)
    message.success('已吊销')
    await load()
  },
})

const onPageChange = (paginationValue: any) => {
  page.value = (paginationValue.current || 1) - 1
  size.value = paginationValue.pageSize || 20
  load()
}

const canRotate = (record: any) => record.status === 'active' && Number(record.expiresAt) > Date.now()
const statusInfo = (record: any) => {
  if (record.status === 'revoked') return { text: '已吊销', color: 'default' }
  if (Number(record.expiresAt) <= Date.now()) return { text: '已过期', color: 'orange' }
  return { text: '生效中', color: 'green' }
}
const formatTime = (value?: number) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-'
const filterOption = (input: string, option: any) => String(option?.label || '').toLowerCase().includes(input.toLowerCase())

const copySecret = async () => {
  await navigator.clipboard.writeText(secret.value)
  message.success('已复制')
}

load()
</script>

<style scoped lang="less">
.api-token-toolbar {
  margin-bottom: 16px;
}

.editor-body {
  max-height: 68vh;
  overflow-y: auto;
  padding-right: 8px;
}

.form-section {
  padding-bottom: 20px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--app-border);
}

.form-section--last {
  padding-bottom: 0;
  margin-bottom: 0;
  border-bottom: 0;
}

.section-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.section-title {
  margin-bottom: 12px;
  color: var(--app-text-secondary);
  font-size: 16px;
  font-weight: 600;
}

.section-head .section-title {
  margin-bottom: 2px;
}

.section-tip {
  color: var(--app-text-secondary);
  font-size: 12px;
}

.permission-search {
  margin: 14px 0 10px;
}

.permission-list {
  overflow: hidden;
  border: 1px solid var(--app-border);
  border-radius: 6px;
}

.permission-row {
  display: grid;
  grid-template-columns: minmax(280px, 1fr) minmax(360px, 1.5fr);
  gap: 16px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--app-border);
}

.permission-row:last-child {
  border-bottom: 0;
}

.permission-row--risk {
  background: var(--app-error-bg);
}

.permission-resource code {
  color: var(--app-text-secondary);
  font-size: 11px;
}

.resource-name {
  font-weight: 500;
}

.resource-description {
  margin: 4px 0 2px 24px;
  color: var(--app-text-secondary);
  font-size: 12px;
}

.permission-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 20px;
}

.permission-actions :deep(.ant-checkbox-wrapper) {
  margin-inline-start: 0;
}

.selected-summary {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
}

.scope-rule {
  margin-bottom: 16px;
}

.expiry-presets {
  margin-bottom: 12px;
}

.expiry-tip {
  margin-top: 6px;
}

.secret-input {
  display: flex;
  margin-top: 16px;
}

@media (max-width: 900px) {
  .permission-row {
    grid-template-columns: 1fr;
  }
}
</style>
