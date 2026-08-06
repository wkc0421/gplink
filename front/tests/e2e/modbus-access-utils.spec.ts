import { expect, test } from '@playwright/test'
import {
    buildTcpAccessPayload,
    buildTcpNetworkPayload,
    buildMetadataFromRegisterMap,
    ensureQuickResourceId,
    parseRegisterMapText,
    parseSlaveText,
    validateTcpQuickConfig,
    validateSlaveRows,
    validateRegisterRows,
} from '../../src/modules/device-manager-ui/views/device/ModbusAccess/utils'
import type { TcpQuickConfigForm } from '../../src/modules/device-manager-ui/views/device/ModbusAccess/types'

test.describe('Modbus 接入向导工具函数', () => {
    const tcpForm: TcpQuickConfigForm = {
        networkName: 'Modbus TCP 网络组件',
        accessName: 'Modbus TCP 接入网关',
        description: '快速配置',
        host: '0.0.0.0',
        port: 502,
        publicHost: '192.168.1.10',
        publicPort: 1502,
        tlsEnabled: false,
    }

    test('构造 TCP 服务端网络组件和接入网关 payload', () => {
        const network = buildTcpNetworkPayload('tcp-server', tcpForm)
        const access = buildTcpAccessPayload(
            'tcp-server',
            tcpForm,
            'network-server-1',
            'gateway-1',
            ['slave-1', 'slave-2'],
        )

        expect(network).toMatchObject({
            type: 'TCP_SERVER',
            state: 'disabled',
            shareCluster: true,
            configuration: {
                host: '0.0.0.0',
                port: 502,
                publicHost: '192.168.1.10',
                publicPort: 1502,
                secure: false,
                parserType: 'DIRECT',
                parserConfiguration: {},
            },
        })
        expect(network.configuration).not.toHaveProperty('ssl')
        expect(access).toMatchObject({
            provider: 'tcp-server-gateway',
            protocol: 'modbus-rtu.v1',
            transport: 'TCP',
            channel: 'network',
            channelId: 'network-server-1',
            configuration: {
                deviceId: 'gateway-1',
                childDeviceIds: ['slave-1', 'slave-2'],
            },
        })
    })

    test('构造 TCP 客户端 payload 并使用 ssl 字段', () => {
        const form = {
            ...tcpForm,
            host: 'modbus.example.com',
            port: 4000,
            tlsEnabled: true,
            certId: 'cert-1',
        }
        const network = buildTcpNetworkPayload('tcp-client', form)
        const access = buildTcpAccessPayload('tcp-client', form, 'network-client-1', 'gateway-1', ['slave-1'])

        expect(network).toMatchObject({
            type: 'TCP_CLIENT',
            configuration: {
                host: 'modbus.example.com',
                port: 4000,
                ssl: true,
                certId: 'cert-1',
                parserType: 'DIRECT',
            },
        })
        expect(network.configuration).not.toHaveProperty('secure')
        expect(access.provider).toBe('tcp-client-gateway')
        expect(access.configuration).toEqual({ deviceId: 'gateway-1' })
    })

    test('校验 TCP 快速配置必填项、端口和 TLS 证书', () => {
        const errors = validateTcpQuickConfig('tcp-server', {
            ...tcpForm,
            networkName: '',
            accessName: '',
            host: '',
            port: 0,
            publicHost: '',
            publicPort: 65536,
            tlsEnabled: true,
            certId: undefined,
        })

        expect(errors).toEqual(expect.arrayContaining([
            '请输入网络组件名称',
            '请输入接入网关名称',
            '请输入监听地址',
            '监听端口范围应为 1-65535',
            '请输入公网地址',
            '公网端口范围应为 1-65535',
            '启用 TLS 后必须选择证书',
        ]))
    })

    test('失败重试时复用已创建资源 ID', async () => {
        let createCount = 0
        const create = async () => {
            createCount += 1
            return 'created-resource-1'
        }

        const firstId = await ensureQuickResourceId(undefined, create)
        const retryId = await ensureQuickResourceId(firstId, create)

        expect(retryId).toBe('created-resource-1')
        expect(createCount).toBe(1)
    })

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
