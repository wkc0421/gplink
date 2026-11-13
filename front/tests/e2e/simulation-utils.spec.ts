import { expect, test } from '@playwright/test'
import { simulationRequest, queryGranularity } from '../../src/modules/device-manager-ui/views/device/DataQueryTest/simulationUtils'
import type { SimulationForm } from '../../src/modules/device-manager-ui/views/device/DataQueryTest/simulationUtils'
const form = (): SimulationForm => ({ devices: ['d'], propertyId: 'E', range: ['2024-01-01 00:00:00', '2026-01-01 00:00:00'], intervalMinutes: 5, mode: 'INCREMENT', min: '0', max: '1', initial: '9007199254740993', increment: '1', snapshots: false })
test('preserves numeric strings and emits one device and property', () => {
  const r = simulationRequest(form(), 'request-id', Date.UTC(2026, 9, 27))
  expect(r.initial).toBe('9007199254740993'); expect(r.increment).toBe('1'); expect(r.min).toBeUndefined()
  expect(r.endTime - r.startTime).toBe(731 * 86400000)
})
test('calendar leap-day clipping, future and multiple-device validation', () => {
  const f = form(); f.range = ['2024-02-29 12:00:00', '2026-02-28 12:00:00']
  expect(() => simulationRequest(f, 'request-id', Date.UTC(2026, 9, 27))).not.toThrow()
  f.range[1] = '2026-02-28 12:00:01'; expect(() => simulationRequest(f, 'request-id', Date.UTC(2026, 9, 27))).toThrow('两个日历年')
  f.devices = ['a', 'b']; expect(() => simulationRequest(f, 'request-id')).toThrow('一个设备')
})
test('invalid decimals rejected and four allowed intervals retained', () => {
  const f = form(); f.mode = 'RANDOM'
  for (const step of [5,15,30,60]) { f.intervalMinutes = step; expect(simulationRequest(f, 'request-id').intervalMinutes).toBe(step) }
  f.min = 'NaN'; expect(() => simulationRequest(f, 'request-id')).toThrow('十进制')
})
test('two-year handoff stays below query bucket cap', () => {
  const r = simulationRequest(form(), 'request-id')
  expect(queryGranularity(r.startTime, r.endTime)).toEqual({ interval: '1d', historyType: 'month' })
})
