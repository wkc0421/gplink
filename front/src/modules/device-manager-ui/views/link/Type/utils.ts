type NetworkRecord = Record<string, any>

export const getNetworkType = (network: NetworkRecord): string => {
    const type = network.type
    return (typeof type === 'string' ? type : type?.value || type?.id)
        || network.typeObject?.value || network.typeObject?.id || ''
}

export const getNetworkTypeLabel = (network: NetworkRecord, tcpClientLabel: string): string => {
    const type = getNetworkType(network)
    return type === 'TCP_CLIENT' ? tcpClientLabel : network.typeObject?.name || type
}

// TCP clients use host/port/ssl in the backend, unlike MQTT clients.
export const normalizeTcpClientConfiguration = (configuration: NetworkRecord = {}): NetworkRecord => ({
    ...configuration,
    host: configuration.host ?? configuration.remoteHost,
    port: configuration.port ?? configuration.remotePort,
    ssl: configuration.ssl ?? configuration.secure ?? false,
    parserType: configuration.parserType || 'DIRECT',
})

export const getNetworkEndpoints = (network: NetworkRecord): { public: boolean; url: string }[] => {
    const type = getNetworkType(network)
    const schemes: Record<string, string> = {
        TCP_CLIENT: 'tcp', TCP_SERVER: 'tcp', MQTT_CLIENT: 'mqtt', MQTT_SERVER: 'mqtt',
        WEB_SOCKET_SERVER: 'ws', HTTP_SERVER: 'http', COAP_SERVER: 'coap', UDP: 'udp',
    }
    const configurations = network.shareCluster === false
        ? (network.cluster || []).map((item: NetworkRecord) => item.configuration)
        : [network.configuration]
    return configurations.filter(Boolean).flatMap((configuration: NetworkRecord) => {
        const client = type === 'TCP_CLIENT'
        const host = client ? configuration.host ?? configuration.remoteHost
            : configuration.publicHost || configuration.remoteHost || configuration.host
        const port = client ? configuration.port ?? configuration.remotePort
            : configuration.publicPort || configuration.remotePort || configuration.port
        if (!host || port === undefined || port === null || port === '') return []
        const secure = client ? configuration.ssl : configuration.secure
        const scheme = (schemes[type] || type.toLowerCase()) + (secure && type !== 'UDP' ? 's' : '')
        const address = host.includes(':') && !host.startsWith('[') ? `[${host}]` : host
        return [{ public: !client && !!configuration.publicHost, url: `${scheme}://${address}:${port}` }]
    })
}
