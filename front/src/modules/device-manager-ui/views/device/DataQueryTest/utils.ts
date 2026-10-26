import type {
  AggregationFunction,
  DeviceDataQuery,
  DeviceDataQueryMode,
  IntervalHistoryType,
} from '../../../api/legacyDeviceData'

export interface QueryForm {
  mode: DeviceDataQueryMode
  devices: string[]
  property: string
  range?: [string, string]
  aggregation: AggregationFunction
  interval: string
  limit: number | null
  historyType: IntervalHistoryType
}

export const historyTypeOptions = [
  { value: '5m', label: '5 分钟（5m）' },
  { value: '15m', label: '15 分钟（15m）' },
  { value: 'day', label: '按小时（day）' },
  { value: 'month', label: '按天（month）' },
  { value: 'week', label: '按周（周一至周日）' },
  { value: 'year', label: '按月（year）' },
  { value: 'quarter', label: '按季度（1—3、4—6、7—9、10—12月）' },
]

export const aggregationOptions = ['LAST', 'FIRST', 'MIN', 'MAX', 'AVG', 'SUM', 'COUNT']
  .map(value => ({ label: value, value }))
export const aggregationIntervalOptions = [
  ...['1m', '5m', '15m', '30m', '1h', '1d'].map(value => ({ label: value, value })),
  { value: '1w', label: '周（周一至周日）' },
  { value: '1M', label: '1M' },
  { value: '1q', label: '季度（1—3、4—6、7—9、10—12月）' },
]

const timeFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
})

export function formatTime(value: unknown): string {
  if (value === null) return 'null'
  if (value === undefined || value === '') return '—'
  // Aggregation times already formatted by the server are Beijing wall-clock text.
  if (typeof value === 'string' && /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) return value
  const date = new Date(typeof value === 'string' && /^\d+$/.test(value) ? Number(value) : value as number)
  if (!Number.isFinite(date.getTime())) return String(value)
  const parts = Object.fromEntries(timeFormatter.formatToParts(date).map(part => [part.type, part.value]))
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}

export function parseTime(value: string): number | undefined {
  if (!/^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/.test(value)) return undefined
  const timestamp = Date.parse(`${value.replace(' ', 'T')}+08:00`)
  return Number.isFinite(timestamp) && formatTime(timestamp) === value ? timestamp : undefined
}

export function buildQuery(form: QueryForm): DeviceDataQuery {
  const devices = [...new Set(form.devices.filter(Boolean))]
  if (!devices.length) throw new Error('请至少选择一个设备')
  if (devices.length > 500) throw new Error('一次最多查询 500 个设备')
  const property = form.property.trim()
  if (!property) throw new Error('请输入属性 ID')
  const from = parseTime(form.range?.[0] || '')
  const to = parseTime(form.range?.[1] || '')
  if (from === undefined || to === undefined) throw new Error('请选择完整、有效的北京时间范围')
  if (to <= from) throw new Error('结束时间必须晚于开始时间')
  const body: DeviceDataQuery['body'] = { devices, properties: [property] }

  if (form.mode === 'aggregation') {
    if (!aggregationOptions.some(option => option.value === form.aggregation)) throw new Error('请选择聚合函数')
    if (!aggregationIntervalOptions.some(option => option.value === form.interval)) throw new Error('请选择聚合间隔')
    if (form.limit === null || !Number.isInteger(form.limit) || form.limit < 1 || form.limit > 1000) {
      throw new Error('每台设备的结果上限须为 1～1000 的整数')
    }
    if (devices.length * form.limit > 100_000) throw new Error('设备数量 × 结果上限不能超过 100000')
    body.aggregationRequest = {
      query: { from, to, interval: form.interval, format: 'yyyy-MM-dd HH:mm:ss', limit: form.limit },
      columns: [{ property, alias: 'value', agg: form.aggregation }],
    }
    return { path: '/devices/agg/_query', body }
  }

  if (form.mode === 'history' && !historyTypeOptions.some(option => option.value === form.historyType)) {
    throw new Error('请选择区间类型')
  }
  return {
    path: form.mode === 'history' ? `/devices/interval/${form.historyType}/history` : '/devices/interval',
    params: { startTime: from, endTime: to },
    body,
  }
}

export interface ResultRow {
  key: string
  deviceId: string
  property: string
  time?: unknown
  value?: unknown
  startTime?: unknown
  endTime?: unknown
  startValue?: unknown
  endValue?: unknown
}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  value !== null && typeof value === 'object' && !Array.isArray(value)

export function resultRows(mode: DeviceDataQueryMode, result: unknown, property: string): ResultRow[] {
  if (!Array.isArray(result)) throw new Error('接口返回格式异常：result 应为数组，请查看原始响应')
  if (mode === 'aggregation') {
    return result.flatMap((group, groupIndex) => {
      if (!isRecord(group)) throw new Error('聚合结果格式异常，请查看原始响应')
      return Object.entries(group).flatMap(([deviceId, points]) => {
        if (!Array.isArray(points)) throw new Error('聚合结果格式异常，请查看原始响应')
        return points.map((point, index) => {
          if (!isRecord(point)) throw new Error('聚合数据格式异常，请查看原始响应')
          return { key: `${groupIndex}:${deviceId}:${index}`, deviceId, property, time: point.time, value: point.value }
        })
      })
    })
  }
  const data = mode === 'history' ? result.flatMap(group => {
    if (!Array.isArray(group)) throw new Error('区间历史结果格式异常，请查看原始响应')
    return group
  }) : result
  return data.map((item, index) => {
    if (!isRecord(item) || typeof item.deviceId !== 'string') throw new Error('区间结果格式异常，请查看原始响应')
    return {
      key: String(index), deviceId: item.deviceId,
      property: typeof item.property === 'string' ? item.property : property,
      startTime: item.startTime, endTime: item.endTime,
      startValue: item.startValue, endValue: item.endValue,
    }
  })
}

export function displayValue(value: unknown): string {
  if (value === undefined) return '—'
  if (value === null) return 'null'
  return typeof value === 'object' ? JSON.stringify(value) : String(value)
}
