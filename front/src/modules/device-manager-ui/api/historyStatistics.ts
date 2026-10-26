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
