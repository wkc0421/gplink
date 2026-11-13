import type { SimulationRequest } from '../../../api/simulation'
import { parseTime } from './utils'

export interface SimulationForm {
  devices: string[]; propertyId: string; range?: [string, string]; intervalMinutes: number
  mode: 'RANDOM' | 'INCREMENT'; min: string; max: string; initial: string; increment: string; snapshots: boolean
}
export function simulationRequest(form: SimulationForm, requestId: string, now = Date.now()): SimulationRequest {
  if (form.devices.length !== 1 || !form.propertyId) throw new Error('请选择一个设备和一个数值型采集项')
  const startTime = parseTime(form.range?.[0] || ''), endTime = parseTime(form.range?.[1] || '')
  if (startTime === undefined || endTime === undefined || endTime <= startTime || endTime > now) throw new Error('请选择有效的历史时间范围，结束时间不能晚于现在')
  const start = new Date(startTime + 8 * 3600000)
  const year = start.getUTCFullYear() + 2, month = start.getUTCMonth()
  const day = Math.min(start.getUTCDate(), new Date(Date.UTC(year, month + 1, 0)).getUTCDate())
  const maximum = Date.UTC(year, month, day, start.getUTCHours(), start.getUTCMinutes(), start.getUTCSeconds()) - 8 * 3600000
  if (endTime > maximum) throw new Error('时间跨度不能超过两个日历年')
  if (![5, 15, 30, 60].includes(form.intervalMinutes)) throw new Error('请选择有效采样间隔')
  const decimal = /^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/
  const values = form.mode === 'RANDOM' ? [form.min, form.max] : [form.initial, form.increment]
  if (values.some(value => !decimal.test(value.trim()) || value.length > 128)) throw new Error('请输入有效十进制数值')
  return {
    deviceId: form.devices[0], propertyId: form.propertyId, requestId, startTime, endTime,
    intervalMinutes: form.intervalMinutes, mode: form.mode, snapshots: form.snapshots,
    ...(form.mode === 'RANDOM' ? { min: form.min.trim(), max: form.max.trim() } : { initial: form.initial.trim(), increment: form.increment.trim() }),
  }
}
export function queryGranularity(start: number, end: number) {
  const hours = (end - start) / 3600000
  return hours <= 950 ? { interval: '1h', historyType: 'day' as const }
    : hours / 24 <= 950 ? { interval: '1d', historyType: 'month' as const }
      : { interval: '1M', historyType: 'year' as const }
}
export const stageNames: Record<string, string> = {
  RAW: '写入原始历史', VERIFY_RAW: '校验原始历史', HOURLY: '刷新小时聚合', DAILY: '刷新日聚合',
  MONTHLY: '刷新月聚合', SNAPSHOTS: '生成小时／日快照', VERIFY: '最终校验', DONE: '全部完成',
}
export const statusNames: Record<string, string> = { PENDING: '等待执行', RUNNING: '执行中', SUCCESS: '成功', FAILED: '失败' }
