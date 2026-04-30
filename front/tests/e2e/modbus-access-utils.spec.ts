import { expect, test } from '@playwright/test'
import {
    buildMetadataFromRegisterMap,
    parseRegisterMapText,
    parseSlaveText,
    validateSlaveRows,
    validateRegisterRows,
} from '../../src/modules/device-manager-ui/views/device/ModbusAccess/utils'

test.describe('Modbus 接入向导工具函数', () => {
    test('解析 registerMap CSV 并兼容字段别名', () => {
        const text = [
            'propertyId,propertyName,fc,addr,qty,dataType,order,scale,offset,writable,unit',
            'temperature,温度,0x03,10,2,FLOAT32,CDAB,0.1,1,false,℃',
        ].join('\n')

        const result = parseRegisterMapText(text)

        expect(result.errors).toEqual([])
        expect(result.rows[0]).toMatchObject({
            propertyId: 'temperature',
            propertyName: '温度',
            functionCode: 3,
            address: 10,
            quantity: 2,
            dataType: 'FLOAT32',
            byteOrder: 'CDAB',
            scale: 0.1,
            offset: 1,
            writable: false,
            unit: '℃',
        })
    })

    test('解析 Excel 粘贴的从机列表并自动生成缺失设备信息', () => {
        const text = [
            'slaveId\tdeviceName',
            '1\t一号从机',
            '2\t',
        ].join('\n')

        const result = parseSlaveText(text, 'gateway-01')

        expect(result.errors).toEqual([])
        expect(result.rows.map(item => item.deviceId)).toEqual(['gateway-01_1', 'gateway-01_2'])
        expect(result.rows[1].deviceName).toBe('从机2')
    })

    test('校验 writable 与功能码关系', () => {
        const result = parseRegisterMapText([
            'propertyId,functionCode,address,quantity,dataType,writable',
            'setpoint,3,0,1,INT16,true',
        ].join('\n'))

        expect(result.errors).toContain('registerMap 第 1 行: writable=true 时功能码必须是 5/6/15/16')
    })

    test('导入时不静默接受错误数据类型和字节序', () => {
        const result = parseRegisterMapText([
            'propertyId,functionCode,address,quantity,dataType,byteOrder',
            'badType,3,0,1,INT128,AABB',
        ].join('\n'))

        expect(result.errors).toContain('registerMap 第 1 行: dataType 只支持 BIT/INT16/UINT16/INT32/UINT32/FLOAT32/INT64/FLOAT64')
        expect(result.errors).toContain('registerMap 第 1 行: byteOrder 只支持 ABCD/CDAB/BADC/DCBA')
    })

    test('空点位和空从机列表不能通过校验', () => {
        expect(validateRegisterRows([])).toContain('registerMap 至少需要配置一行点位')
        expect(validateSlaveRows([])).toContain('从机列表至少需要配置一行从机')
    })

    test('registerMap 同步物模型时保留无关属性并按 propertyId upsert', () => {
        const existing = JSON.stringify({
            properties: [
                {
                    id: 'temperature',
                    name: '旧温度',
                    valueType: { type: 'int' },
                    expands: { source: 'device', type: ['read'], custom: true },
                },
                {
                    id: 'humidity',
                    name: '湿度',
                    valueType: { type: 'int' },
                    expands: { source: 'device', type: ['read'] },
                },
            ],
            functions: [],
            events: [],
            tags: [],
        })
        const rows = parseRegisterMapText([
            'propertyId,propertyName,functionCode,address,quantity,dataType',
            'temperature,温度,3,0,2,FLOAT32',
        ].join('\n')).rows

        expect(validateRegisterRows(rows)).toEqual([])
        const metadata = buildMetadataFromRegisterMap(rows, existing)

        expect(metadata.properties).toHaveLength(2)
        expect(metadata.properties.find(item => item.id === 'temperature')).toMatchObject({
            name: '温度',
            valueType: { type: 'float' },
            expands: { source: 'device', type: ['read'], custom: true },
        })
        expect(metadata.properties.find(item => item.id === 'humidity')?.name).toBe('湿度')
    })
})
