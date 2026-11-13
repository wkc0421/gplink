import { request } from '@jetlinks-web/core'

export interface LatestPropertyValue {
  property: string
  value: unknown
  timestamp: number
}

export interface LatestDeviceRow {
  deviceId: string
  deviceName: string
  latestTimestamp: number | null
  properties: LatestPropertyValue[]
}

export interface LatestDevicePage {
  columns: Array<{ id: string; name: string }>
  data: LatestDeviceRow[]
  total: number
}

const latestDataPath = (productId: string) =>
  `/device-instance/product/${encodeURIComponent(productId)}/latest-data`

export const queryLatestDeviceData = (productId: string, pageIndex: number, pageSize: number) =>
  request.get<LatestDevicePage>(latestDataPath(productId), { pageIndex, pageSize }, { hiddenError: true, timeout: 120_000 })

export const exportLatestDeviceData = (productId: string) =>
  request.get(`${latestDataPath(productId)}/export.xlsx`, {}, { responseType: 'blob', hiddenError: true, timeout: 120_000 })
