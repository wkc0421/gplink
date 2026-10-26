import { expect, test } from '@playwright/test'
import {
  aggregationIntervalOptions, historyTypeOptions, buildQuery, displayValue, formatTime, parseTime, resultRows,
} from '../../src/modules/device-manager-ui/views/device/DataQueryTest/utils'
import type { QueryForm } from '../../src/modules/device-manager-ui/views/device/DataQueryTest/utils'

function form(overrides: Partial<QueryForm> = {}): QueryForm {
  return {
    mode: 'aggregation', devices: ['meter-1', 'meter-2', 'meter-1'], property: ' E ',
    range: ['2026-10-19 00:00:00', '2026-10-20 00:00:00'],
    aggregation: 'LAST', interval: '1h', limit: 100, historyType: 'day', ...overrides,
  }
}

test('builds a single-column aggregation request and deduplicates device IDs', () => {
  expect(buildQuery(form())).toEqual({
    path: '/devices/agg/_query',
    body: {
      devices: ['meter-1', 'meter-2'], properties: ['E'],
      aggregationRequest: {
        query: { from: Date.UTC(2026, 9, 18, 16), to: Date.UTC(2026, 9, 19, 16), interval: '1h', format: 'yyyy-MM-dd HH:mm:ss', limit: 100 },
        columns: [{ property: 'E', alias: 'value', agg: 'LAST' }],
      },
    },
  })
})

for (const interval of ['1w', '1q']) {
  test(`offers ${interval} and preserves partial-period request bounds`, () => {
    expect(aggregationIntervalOptions.find(option => option.value === interval)?.label)
      .toBe(interval === '1w' ? '周（周一至周日）' : '季度（1—3、4—6、7—9、10—12月）')
    const query = buildQuery(form({
      interval, range: ['2026-09-16 12:30:00', '2026-10-20 18:45:00'],
    }))
    expect(query.path).toBe('/devices/agg/_query')
    expect(query.body.aggregationRequest?.query).toEqual({
      interval, from: Date.UTC(2026, 8, 16, 4, 30), to: Date.UTC(2026, 9, 20, 10, 45),
      format: 'yyyy-MM-dd HH:mm:ss', limit: 100,
    })
  })
}

test('sends interval timestamps as query parameters rather than body fields', () => {
  const query = buildQuery(form({ mode: 'interval' }))
  expect(query.path).toBe('/devices/interval')
  expect(query.params).toEqual({ startTime: Date.UTC(2026, 9, 18, 16), endTime: Date.UTC(2026, 9, 19, 16) })
  expect(query.body).toEqual({ devices: ['meter-1', 'meter-2'], properties: ['E'] })
})

for (const historyType of ['5m', '15m', 'day', 'month', 'year', 'week', 'quarter'] as const) {
  test(`uses the backend interval-history type ${historyType}`, () => {
    expect(buildQuery(form({ mode: 'history', historyType })).path).toBe(`/devices/interval/${historyType}/history`)
  })
}

for (const historyType of ['week', 'quarter'] as const) {
  test(`offers ${historyType} history without expanding partial request bounds`, () => {
    expect(historyTypeOptions.find(option => option.value === historyType)?.label)
      .toBe(historyType === 'week' ? '按周（周一至周日）' : '按季度（1—3、4—6、7—9、10—12月）')
    const query = buildQuery(form({
      mode: 'history', historyType, range: ['2024-02-29 12:30:00', '2024-07-03 18:45:00'],
    }))
    expect(query).toEqual({
      path: `/devices/interval/${historyType}/history`,
      params: { startTime: Date.UTC(2024, 1, 29, 4, 30), endTime: Date.UTC(2024, 6, 3, 10, 45) },
      body: { devices: ['meter-1', 'meter-2'], properties: ['E'] },
    })
  })
}

test('rejects missing inputs, invalid dates and reversed time ranges', () => {
  expect(() => buildQuery(form({ devices: [] }))).toThrow('选择一个设备')
  expect(() => buildQuery(form({ property: ' ' }))).toThrow('属性 ID')
  expect(() => buildQuery(form({ range: undefined }))).toThrow('时间')
  expect(() => buildQuery(form({ range: ['2026-02-30 00:00:00', '2026-03-02 00:00:00'] }))).toThrow('有效')
  expect(() => buildQuery(form({ range: ['2026-10-20 00:00:00', '2026-10-19 00:00:00'] }))).toThrow('晚于')
})

test('enforces device and aggregation cell limits before sending a request', () => {
  const devices = Array.from({ length: 500 }, (_, i) => `meter-${i}`)
  expect(buildQuery(form({ devices, limit: 200 })).body.devices).toHaveLength(500)
  expect(() => buildQuery(form({ devices: [...devices, 'extra'] }))).toThrow('500')
  expect(() => buildQuery(form({ devices, limit: 201 }))).toThrow('100000')
  for (const limit of [null, 0, 1.5, 1001, NaN]) expect(() => buildQuery(form({ limit }))).toThrow('整数')
})

test('parses and formats Beijing time independently of the browser time zone', () => {
  const timestamp = Date.UTC(2026, 9, 19, 16)
  expect(parseTime('2026-10-20 00:00:00')).toBe(timestamp)
  expect(formatTime(timestamp)).toBe('2026-10-20 00:00:00')
  expect(formatTime(String(timestamp))).toBe('2026-10-20 00:00:00')
  expect(formatTime('2026-10-20 00:00:00')).toBe('2026-10-20 00:00:00')
  expect(parseTime('2026-02-30 00:00:00')).toBeUndefined()
  expect(parseTime('2026-10-20 25:00:00')).toBeUndefined()
})

test('flattens aggregation groups while preserving device IDs and duplicate timestamps', () => {
  const rows = resultRows('aggregation', [
    { 'meter-1': [{ time: '2026-10-20 00:00:00', value: 0 }, { time: '2026-10-20 00:00:00', value: null }] },
    { 'meter-2': [{ value: '9007199254740993.1234' }] },
  ], 'E')
  expect(rows).toHaveLength(3)
  expect(rows.map(row => row.deviceId)).toEqual(['meter-1', 'meter-1', 'meter-2'])
  expect(rows.map(row => row.value)).toEqual([0, null, '9007199254740993.1234'])
  expect(new Set(rows.map(row => row.key)).size).toBe(3)
})

test('flattens interval history without treating a missing boundary as zero', () => {
  const rows = resultRows('history', [
    [{ deviceId: 'meter-1', property: 'E', startTime: 0, endTime: 1000, startValue: 0, endValue: null }],
    [{ deviceId: 'meter-2', property: 'E', startTime: 0, endTime: 1000, endValue: '12.50' }],
  ], 'E')
  expect(rows).toHaveLength(2)
  expect(rows[0].startValue).toBe(0)
  expect(rows[0].endValue).toBeNull()
  expect(rows[1].startValue).toBeUndefined()
  expect(resultRows('interval', [{ deviceId: 'meter-1', startValue: 1, endValue: 2 }], 'E')[0].property).toBe('E')
})

test('distinguishes an empty result from malformed payloads', () => {
  expect(resultRows('aggregation', [{ 'meter-1': [] }], 'E')).toEqual([])
  expect(resultRows('interval', [], 'E')).toEqual([])
  expect(resultRows('history', [[], []], 'E')).toEqual([])
  expect(() => resultRows('aggregation', undefined, 'E')).toThrow('result 应为数组')
  expect(() => resultRows('aggregation', [{ 'meter-1': {} }], 'E')).toThrow('格式异常')
  expect(() => resultRows('history', [{ deviceId: 'meter-1' }], 'E')).toThrow('格式异常')
})

test('displays explicit null, missing fields, zero and exact decimal text separately', () => {
  expect(displayValue(0)).toBe('0')
  expect(displayValue(null)).toBe('null')
  expect(displayValue(undefined)).toBe('—')
  expect(displayValue('9007199254740993.1234')).toBe('9007199254740993.1234')
  expect(displayValue({ nested: [0, null] })).toBe('{"nested":[0,null]}')
})
