import { request } from '@jetlinks-web/core'

export type DeviceDataQueryMode = 'aggregation' | 'interval' | 'history'
export type IntervalHistoryType = '5m' | '15m' | 'day' | 'month' | 'year' | 'week' | 'quarter'
export type AggregationFunction = 'MIN' | 'MAX' | 'AVG' | 'SUM' | 'COUNT' | 'FIRST' | 'LAST'

export interface DeviceDataQuery {
  path: '/devices/agg/_query' | '/devices/interval' | `/devices/interval/${IntervalHistoryType}/history`
  params?: { startTime: number; endTime: number }
  body: {
    devices: string[]
    properties: string[]
    aggregationRequest?: {
      query: { from: number; to: number; interval: string; format: string; limit: number }
      columns: { property: string; alias: string; agg: AggregationFunction }[]
    }
  }
}

export interface DeviceDataResponse {
  status: number
  success: boolean
  message?: string
  result?: unknown
}

export interface QueryDeviceOption {
  id: string
  name?: string
  productName?: string
}

export const queryDeviceOptions = (keyword: string, pageIndex: number) => request.post<{
  data: QueryDeviceOption[]
  total: number
}>('/device-instance/_query', {
  pageIndex,
  pageSize: 50,
  sorts: [{ name: 'name', order: 'asc' }, { name: 'id', order: 'asc' }],
  terms: keyword ? [{ terms: [
    { column: 'name', termType: 'like', value: `%${keyword}%` },
    { column: 'id', termType: 'like', value: `%${keyword}%`, type: 'or' },
  ] }] : [],
}, { hiddenError: true })

// The shared client supplies /api and the current login credentials.
export const queryLegacyDeviceData = (query: DeviceDataQuery): Promise<DeviceDataResponse> => request.post<unknown>(
  `/v1/device-data${query.path}`,
  query.body,
  { params: query.params, timeout: 35_000, hiddenError: true },
)
