import { expect, test } from '@playwright/test'
import { buildTcpServerPayload, tcpIdentifiers, validateTcpEndpoint, validIp } from '../../src/modules/device-manager-ui/views/device/TcpServerAccess/utils'
import menu from '../../src/modules/device-manager-ui/baseMenu.json'

test('TCP names and IDs consistently derive from case-sensitive identification ID', () => {
  expect(tcpIdentifiers('Abc123')).toEqual({ networkId: 'tcp_server_Abc123', accessId: 'tcp_gateway_Abc123', networkName: 'TCP服务端_Abc123', accessName: 'TCP网关_Abc123', protocolName: 'TCP协议_Abc123', productName: 'TCP产品_Abc123' })
})

test('listen on local interfaces and advertise the public address without MQTT credentials', () => {
  const payload = buildTcpServerPayload('abc123', '203.0.113.10', 4001)
  expect(payload.id).toBe('tcp_server_abc123')
  expect(payload.type).toBe('TCP_SERVER')
  expect(payload.configuration).toEqual({ host: '0.0.0.0', port: 4001, publicHost: '203.0.113.10', publicPort: 4001, secure: false, parserType: 'DIRECT', parserConfiguration: {}, tcpKeepAlive: true })
  expect(payload.configuration).not.toHaveProperty('username')
  expect(payload.configuration).not.toHaveProperty('clientId')
})

test('cluster payload is confined to the current node; IPv6 uses a local wildcard', () => {
  const payload = buildTcpServerPayload('abc123', '2001:db8::1', 4001, 'node1')
  expect(payload.shareCluster).toBe(false)
  expect(payload).not.toHaveProperty('configuration')
  expect(payload.cluster?.[0]).toMatchObject({ serverId: 'node1', configuration: { host: '::', port: 4001, publicHost: '2001:db8::1' } })
})

test('reject malformed IPs and invalid ports before submitting resources', () => {
  for (const ip of ['256.1.1.1', '1.2.3', 'http://1.2.3.4', '1.2.3.4:4001', '001.2.3.4', 'abc', '[::1]', '::gg', 'fe80::1%eth0']) expect(validIp(ip)).toBe(false)
  for (const ip of ['203.0.113.10', '192.168.1.1', '2001:db8::1']) expect(validIp(ip)).toBe(true)
  for (const port of [undefined, 0, -1, 65536, 1.5, NaN]) expect(() => validateTcpEndpoint('203.0.113.10', port)).toThrow('端口')
  expect(() => validateTcpEndpoint('203.0.113.10', 65535)).not.toThrow()
})

test('menu registers a distinct TCP route and its own protocol upload permission', () => {
  const collect = (value: any): any[] => Array.isArray(value) ? value.flatMap(collect) : value && typeof value === 'object' ? [value, ...Object.values(value).flatMap(collect)] : []
  const entries = collect(menu).filter(item => item.code === 'device/TcpServerAccess')
  expect(entries).toHaveLength(1)
  expect(entries[0]).toMatchObject({ name: 'TCP服务端向导', url: '/iot/device/TcpServerAccess' })
  expect(entries[0].buttons.find((button: any) => button.id === 'uploadProtocol').permissions).toContainEqual({ permission: 'protocol-supports', actions: ['query', 'save'] })
})
