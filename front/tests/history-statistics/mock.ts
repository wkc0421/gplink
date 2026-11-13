import type { HistoryManagementRow, HistoryQuery, HistoryBatchItem } from '../../src/modules/device-manager-ui/api/historyStatistics'
const readonly = new URLSearchParams(location.search).has('readonly')
export const useAuthStore = () => ({ hasPermission: (permission: string) => !readonly && permission.startsWith('device/HistoryStatistics:') })
const products = [{ id: 'p1', name: '电表产品' }, { id: 'p2', name: '水表产品' }]
const devices = [{ id: 'd1', name: '一号电表', productId: 'p1', productName: '电表产品' }, { id: 'd2', name: '二号水表', productId: 'p2', productName: '水表产品' }]
const properties = [
  { productId: 'p1', propertyId: 'E', propertyName: '累计电量', valueType: 'double' },
  { productId: 'p1', propertyId: 'X', propertyName: '备用电量', valueType: 'long' },
  ...Array.from({ length: 21 }, (_, i) => ({ productId: 'p1', propertyId: `P${String(i).padStart(2, '0')}`, propertyName: `表码${i}`, valueType: 'double' })),
  { productId: 'p2', propertyId: 'E', propertyName: '累计水量', valueType: 'float' },
  { productId: 'p2', propertyId: 'X', propertyName: '备用水量', valueType: 'double' },
]
const defaults = new Map(properties.filter(p => p.productId === 'p1' && p.propertyId !== 'X').map(p => [`${p.productId}/${p.propertyId}`, true]))
const overrides = new Map<string, boolean>([['d2/E', true], ['d1/C', false]])
const calls: Array<{ method: string; path: string; body?: any }> = []
const failures = new Set<string>()
const requestFailures = new Set<string>()
let taskStatus = 'RUNNING'
const allRows = (scope: string): HistoryManagementRow[] => {
  if (scope === 'PRODUCT') return properties.map(p => ({ ...p, scope: 'PRODUCT', productName: products.find(v => v.id === p.productId)!.name, mode: defaults.get(`${p.productId}/${p.propertyId}`) ? 'ENABLE' : 'DISABLE', enabled: !!defaults.get(`${p.productId}/${p.propertyId}`), eligible: true, propertySource: 'PRODUCT', productEnabled: !!defaults.get(`${p.productId}/${p.propertyId}`), config: { propertyId: p.propertyId, enabled: !!defaults.get(`${p.productId}/${p.propertyId}`), startTime: 1788105600000, status: 'READY', taskId: p.propertyId === 'E' ? 'product-task' : undefined } }))
  return devices.flatMap(d => [...properties.filter(p => p.productId === d.productId), ...(d.id === 'd1' ? [{ productId: d.productId, propertyId: 'C', propertyName: '设备自定义累计', valueType: 'double' }] : [])].map(p => {
    const override = overrides.get(`${d.id}/${p.propertyId}`), productEnabled = !!defaults.get(`${p.productId}/${p.propertyId}`)
    return { ...p, scope: 'DEVICE' as const, productName: d.productName, deviceId: d.id, deviceName: d.name, mode: override === undefined ? 'INHERIT' as const : override ? 'ENABLE' as const : 'DISABLE' as const, enabled: override ?? productEnabled, productEnabled, eligible: true, propertySource: p.propertyId === 'C' ? 'DEVICE' as const : 'PRODUCT' as const, config: { propertyId: p.propertyId, enabled: override ?? productEnabled, status: 'READY', taskId: p.propertyId === 'E' ? `${d.id}-task` : undefined } }
  }))
}
const page = (data: any[], body: any) => ({ data: data.slice((body.pageIndex || 0) * (body.pageSize || 50), ((body.pageIndex || 0) + 1) * (body.pageSize || 50)), total: data.length, pageIndex: body.pageIndex || 0, pageSize: body.pageSize || 50 })
const ok = (result: any) => Promise.resolve({ success: true, status: 200, result: structuredClone(result) })
const check = (method: string, path: string, body?: any) => { calls.push({ method, path, body: body === undefined ? undefined : JSON.parse(JSON.stringify(body)) }); if (requestFailures.delete(path)) throw new Error('模拟读取失败') }
export const request = {
  get(path: string) { check('GET', path); if (path.includes('/tasks/')) return ok({ id: path.split('/').pop(), status: taskStatus, processed: 1, total: 3 }); throw new Error(`Unexpected ${path}`) },
  post(path: string, body: any = {}) {
    check('POST', path, body)
    if (path === '/device-product/_query' || path === '/device-instance/_query') {
      let data: any[] = path.includes('product') ? products : devices
      const search = body.terms?.find((term: any) => term.terms)?.terms?.[0]?.value?.replaceAll('%', '')
      const ids = body.terms?.find((term: any) => term.column === 'productId')?.value
      if (search) data = data.filter(item => `${item.name} ${item.id}`.includes(search))
      if (ids?.length) data = data.filter(item => ids.includes(item.productId))
      return ok(page(data, body))
    }
    if (path.endsWith('/_batch')) {
      const items = body.items.map((item: HistoryBatchItem) => {
        const key = `${item.scope === 'DEVICE' ? item.deviceId : item.productId}/${item.propertyId}`
        if (failures.delete(key)) return { ...item, success: false, message: '模拟项目失败，请重试' }
        const map = item.scope === 'DEVICE' ? overrides : defaults, before = map.get(key)
        if (item.mode === 'INHERIT') map.delete(key); else map.set(key, item.mode === 'ENABLE')
        return { ...item, success: true, changed: before !== map.get(key) }
      })
      return ok({ total: items.length, succeeded: items.filter((v: any) => v.success).length, failed: items.filter((v: any) => !v.success).length, items })
    }
    if (path.endsWith('/_query')) {
      const query: HistoryQuery = body
      const candidate = path.includes('/candidates/')
      let data = allRows(query.scope).filter(row => (!query.productIds?.length || query.productIds.includes(row.productId)) && (!query.deviceIds?.length || query.deviceIds.includes(row.deviceId!)) && (!query.keyword || `${row.propertyName} ${row.propertyId}`.includes(query.keyword)))
      if (!candidate) data = data.filter(row => (row.scope === 'PRODUCT' ? defaults.has(`${row.productId}/${row.propertyId}`) : row.enabled || row.mode !== 'INHERIT') && (query.enabled === undefined || row.enabled === query.enabled) && (!query.source || (query.source === 'OVERRIDE' ? row.mode !== 'INHERIT' : row.mode === query.source)))
      return ok(page(data, body))
    }
    if (path.endsWith('/_rebuild')) { taskStatus = 'RUNNING'; return ok({ taskId: 'new-task' }) }
    if (path.endsWith('/_cancel')) { taskStatus = 'CANCELLED'; return ok({ id: 'new-task', status: taskStatus }) }
    throw new Error(`Unexpected ${path}`)
  },
}
;(window as any).__historyFixture = { calls, failures, requestFailures, defaults, overrides, allRows }
