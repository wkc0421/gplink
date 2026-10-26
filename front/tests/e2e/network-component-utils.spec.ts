import { expect, test } from '@playwright/test'
import {
    getNetworkEndpoints, getNetworkTypeLabel, normalizeTcpClientConfiguration,
} from '../../src/modules/device-manager-ui/views/link/Type/utils'

test('TCP client address comes from canonical host/port even when typeObject is unknown', () => {
    const network = { type: 'TCP_CLIENT', typeObject: { name: 'unknown' }, shareCluster: true,
        configuration: { host: '192.168.110.170', port: 10001, ssl: false } }
    expect(getNetworkTypeLabel(network, 'TCP Client')).toBe('TCP Client')
    expect(getNetworkEndpoints(network)).toEqual([{ public: false, url: 'tcp://192.168.110.170:10001' }])
})

test('node-specific TLS clients and IPv6 addresses render valid endpoints', () => {
    const endpoints = getNetworkEndpoints({ type: { value: 'TCP_CLIENT' }, shareCluster: false, cluster: [
        { configuration: { host: '2001:db8::1', port: 10001, ssl: true } },
        { configuration: { host: '192.168.110.170', port: 10002, ssl: false } },
    ] })
    expect(endpoints).toEqual([
        { public: false, url: 'tcps://[2001:db8::1]:10001' },
        { public: false, url: 'tcp://192.168.110.170:10002' },
    ])
})

test('existing server public address and MQTT remote address keep their meanings', () => {
    expect(getNetworkEndpoints({ type: 'TCP_SERVER', configuration: {
        host: '0.0.0.0', port: 8001, publicHost: '218.200.69.180', publicPort: 18001,
    } })).toEqual([{ public: true, url: 'tcp://218.200.69.180:18001' }])
    expect(getNetworkEndpoints({ type: 'MQTT_CLIENT', configuration: {
        remoteHost: 'mqtt.example.com', remotePort: 8883, secure: true,
    } })).toEqual([{ public: false, url: 'mqtts://mqtt.example.com:8883' }])
})

test('partially configured networks do not display undefined endpoints or throw', () => {
    expect(getNetworkEndpoints({ type: 'TCP_CLIENT' })).toEqual([])
    expect(getNetworkEndpoints({ type: 'TCP_CLIENT', shareCluster: false })).toEqual([])
    expect(getNetworkEndpoints({ type: 'TCP_CLIENT', configuration: { host: '192.168.110.170' } })).toEqual([])
})

test('normalizing an existing TCP client preserves parser and connection settings', () => {
    const original = { host: '192.168.110.170', port: 10001, ssl: true, secure: false,
        parserType: 'FIXED_LENGTH', parserConfiguration: { size: 8 }, connectTimeout: 7654 }
    expect(normalizeTcpClientConfiguration(original)).toEqual(original)
    expect(original.host).toBe('192.168.110.170')
})

test('legacy client fields migrate to canonical settings and absent parser defaults to DIRECT', () => {
    expect(normalizeTcpClientConfiguration({ remoteHost: '192.168.110.170', remotePort: 10001, secure: true }))
        .toMatchObject({ host: '192.168.110.170', port: 10001, ssl: true, parserType: 'DIRECT' })
    expect(normalizeTcpClientConfiguration()).toMatchObject({ ssl: false, parserType: 'DIRECT' })
    expect(normalizeTcpClientConfiguration().host).toBeUndefined()
})
