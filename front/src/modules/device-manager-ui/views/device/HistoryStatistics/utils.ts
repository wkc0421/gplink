import type { HistoryBatchItem, HistoryManagementRow, HistoryMode, HistoryTarget } from '../../../api/historyStatistics'

// JSON tuples preserve identity even when an ID contains separators.
export const historyRowKey = (row: HistoryTarget) => JSON.stringify([row.scope, row.scope === 'DEVICE' ? row.deviceId : row.productId, row.propertyId])
export const historyTargetName = (row: HistoryManagementRow) => row.scope === 'DEVICE' ? row.deviceName || row.deviceId : row.productName || row.productId
export const historyModeLabel = (mode: HistoryMode) => ({ ENABLE: '设备单独启用', DISABLE: '设备单独停用', INHERIT: '继承产品' })[mode]
export const historyStatusLabel = (status?: string | null) => status ? ({ IDLE: '等待调度', READY: '就绪', PENDING: '待处理', RUNNING: '处理中', SUCCESS: '已完成', FAILED: '失败', DISABLED: '已停用', SUSPENDED: '已暂停', CANCELLED: '已取消' } as Record<string, string>)[status] || status : '—'
export const batchItems = (rows: HistoryManagementRow[], mode: HistoryMode): HistoryBatchItem[] => rows.map(row => ({ scope: row.scope, productId: row.productId, ...(row.deviceId ? { deviceId: row.deviceId } : {}), propertyId: row.propertyId, mode }))
export function resultValue<T>(response: { success?: boolean; result?: T; message?: string }, message: string): T {
  if (!response.success || response.result === undefined || response.result === null) throw new Error(response.message || message)
  return response.result
}
export const errorText = (error: any) => error?.response?.data?.message || error?.message || '请求失败，请重试'
export function selectionChanged(current: Map<string, HistoryManagementRow>, keys: (string | number)[], visible: HistoryManagementRow[]) {
  const available = new Map([...current, ...visible.map(row => [historyRowKey(row), row] as const)])
  return new Map(keys.map(String).filter(key => available.has(key)).slice(0, 500).map(key => [key, available.get(key)!]))
}
