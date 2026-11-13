// In-memory fixture, never contacts any device or business API.
export const BASE_API = '/api'
export const useMenuStore = () => ({ jumpPage() {} })
const tasks: any[] = []
const ok = (result: any) => Promise.resolve({ success: true, status: 200, result: structuredClone(result) })
export const request = {
  get(path: string) {
    if (path.endsWith('/properties')) return ok([{ propertyId: 'E', name: '累计电量', type: 'double', unit: 'kWh', scale: 2, snapshotEligible: true }])
    if (path.endsWith('/tasks')) return ok(tasks)
    throw new Error(`Unexpected fixture GET ${path}`)
  },
  post(path: string, body: any) {
    if (path === '/device-instance/_query') return ok({ data: [{ id: 'test-meter', name: '测试电表', productName: '测试产品' }, { id: 'other-meter', name: '另一个设备' }], total: 2 })
    if (path.endsWith('/_preview')) return ok({ count: Math.ceil((body.endTime - body.startTime) / (body.intervalMinutes * 60000)), existing: body.min === '99' ? 1 : 0, oldestTime: Date.UTC(2021, 0, 1), rawExists: true, layers: { HOURLY: 'READY', DAILY: 'READY', MONTHLY: 'READY' }, storageReady: true, canInitialize: true, snapshotEnabled: false, productId: 'test-product', firstValue: body.initial || body.min, lastValue: body.max || '123' })
    if (path.endsWith('/tasks')) {
      const task = { id: crypto.randomUUID(), status: body.min === '13' ? 'FAILED' : 'SUCCESS', stage: body.min === '13' ? 'DAILY' : 'DONE', cursor: 96, total: 96, refresh_cursor: 3, request: body, error: body.min === '13' ? '测试刷新失败' : undefined }
      tasks.unshift(task); return ok(task)
    }
    if (path.endsWith('/_retry')) { const task = tasks.find(t => path.includes(t.id)); task.status = 'SUCCESS'; task.stage = 'DONE'; task.error = undefined; return ok(task) }
    if (path.endsWith('/history/pg')) return ok({ data: [{ property: 'E', timestamp: Date.UTC(2025, 0, 1), value: '100.25' }], total: 1 })
    if (path.endsWith('/devices/agg/_query')) return ok([{ 'test-meter': [{ time: '2025-01-01 00:00:00', value: 100 }] }])
    return ok([])
  },
}
