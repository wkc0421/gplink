import type { ProductGroup, SlaveRow } from './types'
import { createRegisterRow, serializeRegisterMap, validateRegisterRows, validateSlaveRows } from './utils'

export const defaultPollingConfig = () => ({
    pollEnabled: false, pollScheduleType: 'FIXED_DELAY', pollIntervalMs: 300000,
    pollCron: '0/30 * * * * ?', pollDeviceIntervalMs: 100, pollFrameIntervalMs: 100,
    pollPropertyIds: [] as string[], pollRetryCount: 0,
    maxReadRegistersPerRequest: 60, maxReadBitsPerRequest: 512, maxReadAddressGap: 2,
})

export const createProductGroup = (): ProductGroup => ({
    key: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
    mode: 'create', form: { id: '', name: '' }, registerRows: [createRegisterRow()],
    slaveRows: [], pollingConfig: defaultPollingConfig(), testPropertyIds: [], testDeviceIds: [],
    status: '未保存', loadVersion: 0,
})

export const groupName = (group: ProductGroup) => group.form.name || group.sourceProduct?.name || group.productId || '未命名产品'

export const groupSignature = (group: ProductGroup) => JSON.stringify({
    registerMap: serializeRegisterMap(group.registerRows), polling: group.pollingConfig,
})

export const pendingUnbind = (baseline: SlaveRow[], groups: ProductGroup[]) => {
    const retained = new Set(groups.flatMap(group => group.slaveRows.map(row => row.deviceId)))
    return baseline.filter(row => !retained.has(row.deviceId))
}

export const nextSlaveId = (groups: ProductGroup[]) => {
    const used = new Set(groups.flatMap(group => group.slaveRows.map(row => Number(row.slaveId))))
    for (let id = 1; id <= 247; id++) if (!used.has(id)) return id
    return undefined
}

export const validateProductGroups = (groups: ProductGroup[]) => {
    const errors: string[] = []
    const products = new Set<string>()
    const addresses = new Map<number, string>()
    const deviceIds = new Map<string, string>()
    if (!groups.length) errors.push('至少需要一个产品组')
    groups.forEach((group, groupIndex) => {
        const prefix = `产品 ${groupIndex + 1}「${groupName(group)}」`
        const productId = group.productId || group.form.id.trim()
        if (group.mode === 'select' && !group.productId) errors.push(`${prefix}: 请选择产品`)
        if (group.mode === 'create' && !group.form.name.trim()) errors.push(`${prefix}: 请输入产品名称`)
        if (productId && products.has(productId)) errors.push(`${prefix}: 产品 ${productId} 重复`)
        if (productId) products.add(productId)
        errors.push(...validateRegisterRows(group.registerRows).map(error => `${prefix}: ${error}`))
        errors.push(...validateSlaveRows(group.slaveRows).map(error => `${prefix}: ${error}`))
        group.slaveRows.forEach((row, rowIndex) => {
            const location = `${prefix} 从机第 ${rowIndex + 1} 行`
            const address = Number(row.slaveId)
            if (addresses.has(address)) errors.push(`${location}: slaveId ${address} 与 ${addresses.get(address)} 重复`)
            else addresses.set(address, location)
            if (row.deviceId) {
                if (deviceIds.has(row.deviceId)) errors.push(`${location}: deviceId ${row.deviceId} 与 ${deviceIds.get(row.deviceId)} 重复`)
                else deviceIds.set(row.deviceId, location)
            }
        })
    })
    return errors
}

export const mergeChildDeviceIds = (existing: unknown, desired: string[], removed: string[]) => {
    const current = Array.isArray(existing) ? existing.map(String) : String(existing || '').split(',')
    const deleted = new Set(removed)
    return [...new Set([...current, ...desired].map(id => id.trim()).filter(id => id && !deleted.has(id)))]
}

// JSON objects can be re-ordered by the database codec; array order remains significant.
export const sameConfiguration = (left: unknown, right: unknown) => {
    const normalize = (value: any): any => Array.isArray(value) ? value.map(normalize)
        : value && typeof value === 'object'
            ? Object.fromEntries(Object.keys(value).sort().map(key => [key, normalize(value[key])])) : value
    return JSON.stringify(normalize(left)) === JSON.stringify(normalize(right))
}

export const assertDeviceOwnership = (device: any, productId: string, gatewayId: string) => {
    if (device.productId !== productId || (device.parentId && device.parentId !== gatewayId)) {
        throw new Error(`设备 ${device.id} 已属于其他产品或采集器，不能覆盖`)
    }
}

export const assertProductAccess = (product: { id: string; name?: string; accessId?: string }, accessId?: string) => {
    if (product.accessId && product.accessId !== accessId) {
        throw new Error(`产品「${product.name || product.id}」已使用其他接入网关，请创建新产品或选择原接入网关`)
    }
}

export const deviceRowSignature = (row: SlaveRow) => JSON.stringify({
    id: row.deviceId, name: row.deviceName, description: row.description || '',
    slaveId: Number(row.slaveId), pollOverrideEnabled: !!row.pollOverrideEnabled,
})
