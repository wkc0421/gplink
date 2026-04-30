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
}

export interface ImportResult<T> {
    rows: T[]
    errors: string[]
}

export interface TestResultRow {
    key: string
    deviceId: string
    propertyId: string
    status: 'success' | 'error'
    value?: unknown
    message?: string
}

export interface DeviceMetadata {
    properties: any[]
    functions: any[]
    events: any[]
    tags: any[]
    [key: string]: any
}
