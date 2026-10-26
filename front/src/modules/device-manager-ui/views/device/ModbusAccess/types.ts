export type RegisterDataType =
    | 'BIT'
    | 'INT16'
    | 'UINT16'
    | 'INT32'
    | 'UINT32'
    | 'FLOAT32'
    | 'INT64'
    | 'FLOAT64'

export type ByteOrder = 'ABCD' | 'CDAB' | 'BADC' | 'DCBA'

export type AccessMode = 'existing' | 'tcp-server' | 'tcp-client'

export type TcpQuickCreateMode = Exclude<AccessMode, 'existing'>

export interface TcpQuickConfigForm {
    networkName: string
    accessName: string
    description?: string
    host: string
    port?: number
    publicHost: string
    publicPort?: number
    tlsEnabled: boolean
    certId?: string
}

export interface QuickCreatedResources {
    networkId?: string
    accessId?: string
    networkState?: string
    accessState?: string
}

export interface RegisterMappingRow {
    key: string
    propertyId: string
    propertyName?: string
    functionCode: number
    address: number
    quantity: number
    dataType: RegisterDataType
    byteOrder: ByteOrder
    scale: number
    offset: number
    writable: boolean
    unit?: string
}

export interface SlaveRow {
    key: string
    slaveId?: number
    deviceId?: string
    deviceName?: string
    description?: string
    autoDeviceId?: boolean
    autoDeviceName?: boolean
    pollOverrideEnabled?: boolean
    persisted?: boolean
    sourceDevice?: Record<string, any>
    createdDevice?: boolean
}

export interface ImportResult<T> {
    rows: T[]
    errors: string[]
}

export interface TestResultRow {
    key: string
    deviceId: string
    propertyId: string
    status: 'success' | 'error' | 'waiting' | 'skipped'
    productName?: string
    productId?: string
    value?: unknown
    message?: string
}

export interface ProductGroup {
    key: string
    mode: 'select' | 'create'
    productId?: string
    form: { id: string; name: string }
    registerRows: RegisterMappingRow[]
    slaveRows: SlaveRow[]
    pollingConfig: Record<string, any>
    testPropertyIds: string[]
    testDeviceIds: string[]
    sourceProduct?: Record<string, any>
    savedSignature?: string
    createdProduct?: boolean
    locked?: boolean
    status: string
    loadVersion: number
    loading?: boolean
}

export interface DeviceMetadata {
    properties: any[]
    functions: any[]
    events: any[]
    tags: any[]
    [key: string]: any
}
