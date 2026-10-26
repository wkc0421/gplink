import { expect, test } from '@playwright/test'
import { createProductGroup, groupSignature, pendingUnbind, nextSlaveId, validateProductGroups, mergeChildDeviceIds, sameConfiguration, assertDeviceOwnership, assertProductAccess } from '../../src/modules/device-manager-ui/views/device/ModbusAccess/groups'
import { createRegisterRow, createSlaveRow, parseRegisterMapText, parseSlaveText } from '../../src/modules/device-manager-ui/views/device/ModbusAccess/utils'

const group = (name: string, address: number) => {
    const value = createProductGroup()
    value.form.name = name
    value.registerRows = [createRegisterRow({ propertyId: 'value' })]
    value.slaveRows = [createSlaveRow(address, 'collector')]
    return value
}

test('产品组拥有独立映射、轮询、从机与测试选择，相同属性 ID 可跨产品使用', () => {
    const a = group('电表', 1)
    const b = group('水表', 14)
    const before = groupSignature(b)
    a.registerRows = parseRegisterMapText('propertyId,address,dataType\nvoltage,10,INT16').rows
    a.slaveRows = parseSlaveText('slaveId,deviceName\n2,电表二', 'collector').rows
    a.pollingConfig.pollIntervalMs = 2000
    a.pollingConfig.pollPropertyIds.push('voltage')
    a.testPropertyIds.push('voltage')
    expect(groupSignature(b)).toBe(before)
    expect(b.slaveRows[0].slaveId).toBe(14)
    expect(b.testPropertyIds).toEqual([])
    expect(b.pollingConfig.pollPropertyIds).toEqual([])
    expect(validateProductGroups([a, b])).toEqual([])
    expect(nextSlaveId([a, b])).toBe(1)
})

test('跨产品检查 slaveId、deviceId 和产品重复并定位到行', () => {
    const a = group('电表', 1)
    const b = group('水表', 1)
    a.productId = b.productId = 'same-product'
    const errors = validateProductGroups([a, b])
    expect(errors.some(error => error.includes('水表') && error.includes('slaveId 1') && error.includes('电表'))).toBeTruthy()
    expect(errors.some(error => error.includes('deviceId collector_1'))).toBeTruthy()
    expect(errors.some(error => error.includes('产品 same-product 重复'))).toBeTruthy()
    expect(validateProductGroups([])).toContain('至少需要一个产品组')
})

test('移除从机或产品组只产生明确待解绑 ID，配置合并保留无关项', () => {
    const a = group('电表', 1)
    const b = group('水表', 14)
    const baseline = [...a.slaveRows, ...b.slaveRows]
    expect(pendingUnbind(baseline, [a]).map(row => row.deviceId)).toEqual(['collector_14'])
    expect(mergeChildDeviceIds(' collector_1, collector_14, outsider ', ['collector_1', 'collector_20'], ['collector_14']))
        .toEqual(['collector_1', 'outsider', 'collector_20'])
    expect(mergeChildDeviceIds(['collector_1'], ['collector_1', 'collector_20'], [])).toEqual(['collector_1', 'collector_20'])
})

test('禁止覆盖其他产品或采集器设备，允许同产品的未绑定设备', () => {
    expect(() => assertDeviceOwnership({ id: 'id', productId: 'p', parentId: 'other' }, 'p', 'collector')).toThrow('不能覆盖')
    expect(() => assertDeviceOwnership({ id: 'id', productId: 'other' }, 'p', 'collector')).toThrow('不能覆盖')
    expect(() => assertDeviceOwnership({ id: 'id', productId: 'p' }, 'p', 'collector')).not.toThrow()
})

test('保存结果核对忽略对象字段顺序，同时检查配置值和数组内容', () => {
    expect(sameConfiguration({ deviceId: 'g', custom: { a: 1, b: 2 }, childDeviceIds: ['a', 'b'] },
        { childDeviceIds: ['a', 'b'], custom: { b: 2, a: 1 }, deviceId: 'g' })).toBeTruthy()
    expect(sameConfiguration({ childDeviceIds: ['a'] }, { childDeviceIds: ['b'] })).toBeFalsy()
})

test('已有产品只允许原接入网关，新建接入同样提前拒绝已绑定产品', () => {
    const product = { id: 'old', name: '旧从机产品', accessId: 'original' }
    expect(() => assertProductAccess(product, 'original')).not.toThrow()
    expect(() => assertProductAccess(product, 'other')).toThrow('旧从机产品')
    expect(() => assertProductAccess(product)).toThrow('选择原接入网关')
    expect(() => assertProductAccess({ id: 'unbound' }, 'new')).not.toThrow()
    expect(() => assertProductAccess({ id: 'unbound' })).not.toThrow()
})
