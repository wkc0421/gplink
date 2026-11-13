export function tcpIdentifiers(id: string) {
  return {
    networkId: `tcp_server_${id}`,
    accessId: `tcp_gateway_${id}`,
    networkName: `TCP服务端_${id}`,
    accessName: `TCP网关_${id}`,
    protocolName: `TCP协议_${id}`,
    productName: `TCP产品_${id}`,
  }
}

export function validIp(value: string) {
  if (value.includes(':')) {
    try { return !/[\[\]\s/%]/.test(value) && new URL(`http://[${value}]`).hostname.length > 2 }
    catch { return false }
  }
  const parts = value.split('.')
  return parts.length === 4 && parts.every(part => /^(0|[1-9]\d{0,2})$/.test(part) && Number(part) <= 255)
}

export function validateTcpEndpoint(publicHost: string, port: number | undefined) {
  if (!validIp(publicHost.trim())) throw new Error('请输入有效的公网 IP（IPv4 或 IPv6，不包含协议前缀或端口）')
  if (!Number.isInteger(port) || Number(port) < 1 || Number(port) > 65535) throw new Error('端口必须为 1～65535 的整数')
}

export function buildTcpServerPayload(id: string, publicHost: string, port: number, nodeId?: string) {
  validateTcpEndpoint(publicHost, port)
  const identifiers = tcpIdentifiers(id)
  const configuration = {
    host: publicHost.trim().includes(':') ? '::' : '0.0.0.0', port, publicHost: publicHost.trim(), publicPort: port,
    secure: false, parserType: 'DIRECT', parserConfiguration: {}, tcpKeepAlive: true,
  }
  return {
    id: identifiers.networkId, name: identifiers.networkName, type: 'TCP_SERVER',
    shareCluster: !nodeId,
    ...(nodeId ? { cluster: [{ serverId: nodeId, configuration }] } : { configuration }),
  }
}
