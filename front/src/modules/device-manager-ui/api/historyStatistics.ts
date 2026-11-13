import { request } from '@jetlinks-web/core'

export interface HistoryStatisticsConfig {
  propertyId: string
  enabled: boolean
  startTime?: number | null
  version?: number
  status?: string | null
  coverageFrom?: number | null
  coverageTo?: number | null
  pendingFrom?: number | null
  pendingTo?: number | null
  lastSuccess?: number | null
  lastFailure?: number | null
  lastError?: string | null
  taskId?: string | null
}

export interface HistoryStatisticsTask {
  id: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED' | string
  processed?: number
  total?: number
  lastError?: string | null
}

const basePath = (productId: string) =>
  `/device-product/${encodeURIComponent(productId)}/history-statistics`

export const queryHistoryStatistics = (productId: string) =>
  request.get<HistoryStatisticsConfig[]>(basePath(productId))

export const updateHistoryStatistics = (
  productId: string,
  propertyId: string,
  enabled: boolean,
) => request.put<HistoryStatisticsConfig>(
  `${basePath(productId)}/${encodeURIComponent(propertyId)}`,
  { enabled },
)

export const rebuildHistoryStatistics = (
  productId: string,
  propertyId: string,
  range: { from: number; to: number },
) => request.post<{ taskId: string }>(
  `${basePath(productId)}/${encodeURIComponent(propertyId)}/_rebuild`,
  range,
)

export const queryHistoryStatisticsTask = (productId: string, taskId: string) =>
  request.get<HistoryStatisticsTask>(
    `${basePath(productId)}/tasks/${encodeURIComponent(taskId)}`,
  )

export const cancelHistoryStatisticsTask = (productId: string, taskId: string) =>
  request.post<HistoryStatisticsTask>(
    `${basePath(productId)}/tasks/${encodeURIComponent(taskId)}/_cancel`,
  )

export type HistoryScope = 'PRODUCT' | 'DEVICE'
export type HistoryMode = 'ENABLE' | 'DISABLE' | 'INHERIT'
export interface HistoryTarget {
  scope: HistoryScope
  productId: string
  deviceId?: string
  propertyId: string
}
export interface HistoryManagementRow extends HistoryTarget {
  productName: string
  deviceName?: string
  propertyName: string
  valueType?: string
  unit?: string
  mode: HistoryMode
  enabled: boolean
  eligible: boolean
  propertySource?: HistoryScope
  productEnabled?: boolean
  policyRevision?: number
  config?: HistoryStatisticsConfig
}
export interface HistoryQuery {
  scope: HistoryScope
  pageIndex: number
  pageSize: number
  productIds?: string[]
  deviceIds?: string[]
  keyword?: string
  enabled?: boolean
  source?: HistoryMode | 'OVERRIDE'
  status?: string
}
export interface HistoryPage<T> { data: T[]; total: number; pageIndex: number; pageSize: number }
export interface HistoryBatchItem extends HistoryTarget { mode: HistoryMode }
export interface HistoryBatchResult {
  total: number
  succeeded: number
  failed: number
  items: Array<HistoryTarget & { success: boolean; changed?: boolean; message?: string }>
}
const managementPath = '/device/history-statistics'
export const queryHistoryManagement = (query: HistoryQuery) =>
  request.post<HistoryPage<HistoryManagementRow>>(`${managementPath}/_query`, query, { hiddenError: true })
export const queryHistoryCandidates = (query: HistoryQuery) =>
  request.post<HistoryPage<HistoryManagementRow>>(`${managementPath}/candidates/_query`, query, { hiddenError: true })
export const batchHistoryManagement = (items: HistoryBatchItem[]) =>
  request.post<HistoryBatchResult>(`${managementPath}/_batch`, { items }, { hiddenError: true })
const deviceHistoryPath = (deviceId: string) => `${managementPath}/device/${encodeURIComponent(deviceId)}`
export const rebuildManagedHistory = (target: HistoryTarget, range: { from: number; to: number }) =>
  target.scope === 'PRODUCT' ? rebuildHistoryStatistics(target.productId, target.propertyId, range)
    : request.post<{ taskId: string }>(`${deviceHistoryPath(target.deviceId!)}/${encodeURIComponent(target.propertyId)}/_rebuild`, range)
export const queryManagedHistoryTask = (target: HistoryTarget, taskId: string) =>
  target.scope === 'PRODUCT' ? queryHistoryStatisticsTask(target.productId, taskId)
    : request.get<HistoryStatisticsTask>(`${deviceHistoryPath(target.deviceId!)}/tasks/${encodeURIComponent(taskId)}`)
export const cancelManagedHistoryTask = (target: HistoryTarget, taskId: string) =>
  target.scope === 'PRODUCT' ? cancelHistoryStatisticsTask(target.productId, taskId)
    : request.post<HistoryStatisticsTask>(`${deviceHistoryPath(target.deviceId!)}/tasks/${encodeURIComponent(taskId)}/_cancel`)
