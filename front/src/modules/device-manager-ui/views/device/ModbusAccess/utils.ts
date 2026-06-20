import type { ByteOrder, DeviceMetadata, ImportResult, RegisterDataType, RegisterMappingRow, SlaveRow } from './types'

export const MODBUS_PROTOCOL_ID = 'modbus-rtu.v1'
export const READ_FUNCTION_CODES = [1, 2, 3, 4]
export const WRITE_FUNCTION_CODES = [5, 6, 15, 16]
export const ALL_FUNCTION_CODES = [...READ_FUNCTION_CODES, ...WRITE_FUNCTION_CODES]

export const DATA_TYPE_OPTIONS: RegisterDataType[] = [
    'BIT',
    'INT16',
    'UINT16',
    'INT32',
    'UINT32',
    'FLOAT32',
    'INT64',
    'FLOAT64',
]

export const BYTE_ORDER_OPTIONS: ByteOrder[] = ['ABCD', 'CDAB', 'BADC', 'DCBA']

export const DEFAULT_COMMUNICATION_CONFIG = {
    responseTimeoutMs: 3000,
    probeIntervalMs: 30000,
    keepOnlineTimeout: 120,
}

export const DEFAULT_COLLECTION_POLICY = {
    collectEnabled: true,
    scanIntervalMs: 5000,
    dispatchIntervalMs: 50,
    storageIntervalMs: 60000,
    responseTimeoutMs: 3000,
}

const DATA_TYPE_MIN_QUANTITY: Record<RegisterDataType, number> = {
    BIT: 1,
    INT16: 1,
    UINT16: 1,
    INT32: 2,
    UINT32: 2,
    FLOAT32: 2,
    INT64: 4,
    FLOAT64: 4,
}

const fcLimit = (functionCode: number) => {
    if (functionCode === 1 || functionCode === 2) return 2000
    if (functionCode === 3 || functionCode === 4) return 125
    if (functionCode === 15) return 1968
    if (functionCode === 16) return 123
    return 1
}

const isBitType = (dataType: RegisterDataType) => dataType === 'BIT'

const isReadableWritableFc = (functionCode: number, dataType: RegisterDataType) =>
    (functionCode === 1 && isBitType(dataType)) || (functionCode === 3 && !isBitType(dataType))

const VALUE_TYPE_MAP: Record<RegisterDataType, string> = {
    BIT: 'boolean',
    INT16: 'int',
    UINT16: 'int',
    INT32: 'long',
    UINT32: 'long',
    FLOAT32: 'float',
    INT64: 'long',
    FLOAT64: 'double',
}

const REGISTER_HEADER_ALIASES: Record<string, keyof RegisterMappingRow> = {
    propertyid: 'propertyId',
    property: 'propertyId',
    id: 'propertyId',
    pointid: 'propertyId',
    propertyname: 'propertyName',
    name: 'propertyName',
    pointname: 'propertyName',
    functioncode: 'functionCode',
    fc: 'functionCode',
    address: 'address',
    addr: 'address',
    quantity: 'quantity',
    qty: 'quantity',
    datatype: 'dataType',
    type: 'dataType',
    byteorder: 'byteOrder',
    order: 'byteOrder',
    scale: 'scale',
    offset: 'offset',
    writable: 'writable',
    write: 'writable',
    unit: 'unit',
}

const SLAVE_HEADER_ALIASES: Record<string, keyof SlaveRow> = {
    slaveid: 'slaveId',
    slave: 'slaveId',
    address: 'slaveId',
    deviceid: 'deviceId',
    id: 'deviceId',
    devicename: 'deviceName',
    name: 'deviceName',
    description: 'description',
    describe: 'description',
}

const normalizeHeader = (value: string) => value.trim().toLowerCase().replace(/[\s_-]/g, '')

const toNumber = (value: unknown, fallback: number) => {
    if (value === undefined || value === null || value === '') return fallback
    const parsed = Number(value)
    return Number.isFinite(parsed) ? parsed : fallback
}

const toBoolean = (value: unknown) => {
    if (typeof value === 'boolean') return value
    const text = String(value ?? '').trim().toLowerCase()
    return ['true', '1', 'yes', 'y', '\u662f', '\u53ef\u5199'].includes(text)
}

export const createRegisterRow = (partial: Partial<RegisterMappingRow> = {}): RegisterMappingRow => ({
    key: partial.key || `${Date.now()}_${Math.random().toString(16).slice(2)}`,
    propertyId: partial.propertyId || '',
    propertyName: partial.propertyName || '',
    functionCode: partial.functionCode ?? 3,
    address: partial.address ?? 0,
    quantity: partial.quantity ?? 1,
    dataType: partial.dataType || 'INT16',
    byteOrder: partial.byteOrder || 'ABCD',
    scale: partial.scale ?? 1,
    offset: partial.offset ?? 0,
    writable: partial.writable ?? false,
    unit: partial.unit || '',
})

export const createSlaveRow = (slaveId?: number, gatewayDeviceId = ''): SlaveRow => {
    const suffix = slaveId || ''
    return {
        key: `${Date.now()}_${Math.random().toString(16).slice(2)}`,
        slaveId,
        deviceId: suffix ? buildSlaveDeviceId(gatewayDeviceId, suffix) : '',
        deviceName: suffix ? `浠庢満${suffix}` : '',
        description: '',
        autoDeviceId: true,
        autoDeviceName: true,
    }
}

export const buildSlaveDeviceId = (gatewayDeviceId: string, slaveId: string | number) => {
    const prefix = (gatewayDeviceId || 'modbus_gateway').replace(/[^\w-]/g, '_')
    return `${prefix}_${slaveId}`
}

const parseDelimitedRows = (text: string, delimiter: string): string[][] => {
    const rows: string[][] = []
    let current = ''
    let row: string[] = []
    let inQuotes = false
    const source = text.replace(/^\uFEFF/, '')

    for (let index = 0; index < source.length; index++) {
        const char = source[index]
        const next = source[index + 1]

        if (char === '"') {
            if (inQuotes && next === '"') {
                current += '"'
                index += 1
            } else {
                inQuotes = !inQuotes
            }
            continue
        }

        if (!inQuotes && char === delimiter) {
            row.push(current.trim())
            current = ''
            continue
        }

        if (!inQuotes && (char === '\n' || char === '\r')) {
            if (char === '\r' && next === '\n') index += 1
            row.push(current.trim())
            if (row.some(item => item !== '')) rows.push(row)
            row = []
            current = ''
            continue
        }

        current += char
    }

    row.push(current.trim())
    if (row.some(item => item !== '')) rows.push(row)
    return rows
}

const parseTableRows = (text: string): string[][] => {
    const firstLine = text.replace(/^\uFEFF/, '').split(/\r?\n/).find(line => line.trim()) || ''
    const delimiter = firstLine.includes('\t') ? '\t' : ','
    return parseDelimitedRows(text, delimiter)
}

const mapRows = <T extends Record<string, any>>(
    text: string,
    aliases: Record<string, keyof T>,
): { records: Partial<T>[]; errors: string[] } => {
    const table = parseTableRows(text)
    if (!table.length) {
        return { records: [], errors: ['Import content is empty'] }
    }

    const headers = table[0].map(item => aliases[normalizeHeader(item)])
    if (!headers.some(Boolean)) {
        return { records: [], errors: ['No valid header row found'] }
    }

    const records = table.slice(1).map((line) => {
        const record: Partial<T> = {}
        headers.forEach((header, index) => {
            if (header) {
                record[header] = line[index] as any
            }
        })
        return record
    })

    return { records, errors: [] }
}

const parseFunctionCode = (value: unknown) => {
    const text = String(value ?? '').trim()
    if (!text) return 3
    if (/^0x/i.test(text)) return Number.parseInt(text, 16)
    return Number(text)
}

const normalizeDataType = (value: unknown): RegisterDataType => {
    const dataType = String(value || 'INT16').trim().toUpperCase() as RegisterDataType
    return DATA_TYPE_OPTIONS.includes(dataType) ? dataType : 'INT16'
}

const normalizeByteOrder = (value: unknown): ByteOrder => {
    const byteOrder = String(value || 'ABCD').trim().toUpperCase() as ByteOrder
    return BYTE_ORDER_OPTIONS.includes(byteOrder) ? byteOrder : 'ABCD'
}

export const parseRegisterMapText = (text: string): ImportResult<RegisterMappingRow> => {
    const { records, errors } = mapRows<RegisterMappingRow>(text, REGISTER_HEADER_ALIASES)
    const rows = records.map(item => createRegisterRow({
        propertyId: String(item.propertyId || '').trim(),
        propertyName: String(item.propertyName || '').trim(),
        functionCode: parseFunctionCode(item.functionCode),
        address: toNumber(item.address, 0),
        quantity: toNumber(item.quantity, 1),
        dataType: String(item.dataType || 'INT16').trim().toUpperCase() as RegisterDataType,
        byteOrder: String(item.byteOrder || 'ABCD').trim().toUpperCase() as ByteOrder,
        scale: toNumber(item.scale, 1),
        offset: toNumber(item.offset, 0),
        writable: toBoolean(item.writable),
        unit: String(item.unit || '').trim(),
    }))

    return { rows, errors: [...errors, ...validateRegisterRows(rows)] }
}

export const parseSlaveText = (text: string, gatewayDeviceId = ''): ImportResult<SlaveRow> => {
    const { records, errors } = mapRows<SlaveRow>(text, SLAVE_HEADER_ALIASES)
    const rows = records.map((item) => {
        const slaveId = Number(item.slaveId)
        const hasDeviceId = !!String(item.deviceId || '').trim()
        const hasDeviceName = !!String(item.deviceName || '').trim()
        const row = createSlaveRow(Number.isFinite(slaveId) ? slaveId : undefined, gatewayDeviceId)
        row.deviceId = String(item.deviceId || row.deviceId || '').trim()
        row.deviceName = String(item.deviceName || row.deviceName || '').trim()
        row.description = String(item.description || '').trim()
        row.autoDeviceId = !hasDeviceId
        row.autoDeviceName = !hasDeviceName
        return row
    })

    return { rows, errors: [...errors, ...validateSlaveRows(rows)] }
}

export const parseRegisterMapValue = (value?: string): RegisterMappingRow[] => {
    if (!value) return []
    try {
        const parsed = JSON.parse(value)
        if (!Array.isArray(parsed)) return []
        return parsed.map(item => createRegisterRow({
            propertyId: item.propertyId ?? item.property ?? '',
            propertyName: item.propertyName ?? item.name ?? '',
            functionCode: item.functionCode ?? item.fc ?? 3,
            address: item.address ?? item.addr ?? 0,
            quantity: item.quantity ?? item.qty ?? 1,
            dataType: normalizeDataType(item.dataType),
            byteOrder: normalizeByteOrder(item.byteOrder),
            scale: item.scale ?? 1,
            offset: item.offset ?? 0,
            writable: item.writable ?? false,
            unit: item.unit ?? '',
        }))
    } catch {
        return []
    }
}

export const serializeRegisterMap = (rows: RegisterMappingRow[]) => JSON.stringify(
    rows
        .filter(item => item.propertyId)
        .map(item => ({
            propertyId: item.propertyId,
            propertyName: item.propertyName || item.propertyId,
            functionCode: Number(item.functionCode),
            address: Number(item.address),
            quantity: Number(item.quantity),
            dataType: item.dataType,
            byteOrder: item.byteOrder,
            scale: Number(item.scale),
            offset: Number(item.offset),
            writable: !!item.writable,
            unit: item.unit || undefined,
        })),
)

export const validateRegisterRows = (rows: RegisterMappingRow[]) => {
    const errors: string[] = []
    const propertyIds = new Set<string>()
    if (!rows.length || !rows.some(row => row.propertyId)) {
        errors.push('registerMap requires at least one property row')
    }

    rows.forEach((row, index) => {
        const prefix = `registerMap row ${index + 1}`
        if (!row.propertyId) {
            errors.push(`${prefix}: propertyId is required`)
        } else if (propertyIds.has(row.propertyId)) {
            errors.push(`${prefix}: propertyId ${row.propertyId} is duplicated`)
        } else {
            propertyIds.add(row.propertyId)
        }

        if (!ALL_FUNCTION_CODES.includes(Number(row.functionCode))) {
            errors.push(`${prefix}: functionCode only supports 1/2/3/4/5/6/15/16`)
        }

        if (!DATA_TYPE_OPTIONS.includes(row.dataType)) {
            errors.push(`${prefix}: dataType only supports ${DATA_TYPE_OPTIONS.join('/')}`)
        }

        if (!BYTE_ORDER_OPTIONS.includes(row.byteOrder)) {
            errors.push(`${prefix}: byteOrder only supports ${BYTE_ORDER_OPTIONS.join('/')}`)
        }

        if (!Number.isInteger(Number(row.address)) || Number(row.address) < 0 || Number(row.address) > 65535) {
            errors.push(`${prefix}: address must be between 0 and 65535`)
        }

        const minQuantity = DATA_TYPE_MIN_QUANTITY[row.dataType] || 1
        if (!Number.isInteger(Number(row.quantity)) || Number(row.quantity) < minQuantity) {
            errors.push(`${prefix}: ${row.dataType} quantity must be at least ${minQuantity}`)
        }

        const quantity = Number(row.quantity)
        const functionCode = Number(row.functionCode)
        if (Number.isInteger(Number(row.address)) && Number.isInteger(quantity)
            && Number(row.address) + quantity - 1 > 65535) {
            errors.push(`${prefix}: address + quantity must not exceed 65535`)
        }

        if (READ_FUNCTION_CODES.includes(functionCode)) {
            if ((functionCode === 1 || functionCode === 2) && !isBitType(row.dataType)) {
                errors.push(`${prefix}: FC1/FC2 only support BIT`)
            }
            if ((functionCode === 3 || functionCode === 4) && isBitType(row.dataType)) {
                errors.push(`${prefix}: FC3/FC4 only support word data types`)
            }
        }

        if (WRITE_FUNCTION_CODES.includes(functionCode)) {
            if ((functionCode === 5 || functionCode === 15) && !isBitType(row.dataType)) {
                errors.push(`${prefix}: FC5/FC15 only support BIT`)
            }
            if ((functionCode === 6 || functionCode === 16) && isBitType(row.dataType)) {
                errors.push(`${prefix}: FC6/FC16 only support word data types`)
            }
            if ((functionCode === 5 || functionCode === 6) && quantity !== 1) {
                errors.push(`${prefix}: FC${functionCode} requires quantity=1`)
            }
        }

        if (Number.isInteger(quantity) && quantity > fcLimit(functionCode)) {
            errors.push(`${prefix}: quantity exceeds FC${functionCode} limit ${fcLimit(functionCode)}`)
        }

        if (row.writable && !WRITE_FUNCTION_CODES.includes(functionCode) && !isReadableWritableFc(functionCode, row.dataType)) {
            errors.push(`${prefix}: writable=true requires FC1/FC3 derived write support or write FC 5/6/15/16`)
        }

    })

    return errors
}

export const validateSlaveRows = (rows: SlaveRow[]) => {
    const errors: string[] = []
    const slaveIds = new Set<number>()
    const deviceIds = new Set<string>()
    if (!rows.length) {
        errors.push('slave list requires at least one slave')
    }

    rows.forEach((row, index) => {
        const prefix = `slave row ${index + 1}`
        const slaveId = Number(row.slaveId)
        if (!Number.isInteger(slaveId) || slaveId < 1 || slaveId > 247) {
            errors.push(`${prefix}: slaveId must be between 1 and 247`)
        } else if (slaveIds.has(slaveId)) {
            errors.push(`${prefix}: slaveId ${slaveId} is duplicated`)
        } else {
            slaveIds.add(slaveId)
        }

        if (!row.deviceId) {
            errors.push(`${prefix}: deviceId is required`)
        } else if (!/^[a-zA-Z0-9_-]+$/.test(row.deviceId)) {
            errors.push(`${prefix}: deviceId only supports letters, numbers, underscore and hyphen`)
        } else if (deviceIds.has(row.deviceId)) {
            errors.push(`${prefix}: deviceId ${row.deviceId} is duplicated`)
        } else {
            deviceIds.add(row.deviceId)
        }
    })

    return errors
}

const parseMetadata = (metadata?: string | DeviceMetadata): DeviceMetadata => {
    const fallback = { properties: [], functions: [], events: [], tags: [] }
    if (!metadata) return fallback
    if (typeof metadata === 'object') {
        return {
            ...fallback,
            ...metadata,
            properties: Array.isArray(metadata.properties) ? metadata.properties : [],
        }
    }
    try {
        const parsed = JSON.parse(metadata)
        return {
            ...fallback,
            ...parsed,
            properties: Array.isArray(parsed.properties) ? parsed.properties : [],
        }
    } catch {
        return fallback
    }
}

export const buildMetadataFromRegisterMap = (
    rows: RegisterMappingRow[],
    existingMetadata?: string | DeviceMetadata,
): DeviceMetadata => {
    const metadata = parseMetadata(existingMetadata)
    const generated = new Map<string, any>()

    rows
        .filter(row => row.propertyId)
        .forEach((row) => {
            const isRead = READ_FUNCTION_CODES.includes(Number(row.functionCode))
            const valueType: Record<string, any> = {
                type: VALUE_TYPE_MAP[row.dataType] || 'string',
            }
            if (row.unit && ['int', 'long', 'float', 'double'].includes(valueType.type)) {
                valueType.unit = row.unit
            }

            generated.set(row.propertyId, {
                id: row.propertyId,
                name: row.propertyName || row.propertyId,
                valueType,
                expands: {
                    source: 'device',
                    type: isRead ? ['read'] : ['write'],
                },
                description: `Modbus ${row.functionCode} @ ${row.address}`,
            })
        })

    const properties = metadata.properties.map((item: any) => {
        const replacement = generated.get(item.id)
        if (!replacement) return item
        generated.delete(item.id)
        return {
            ...item,
            ...replacement,
            expands: {
                ...(item.expands || {}),
                ...replacement.expands,
            },
        }
    })

    return {
        ...metadata,
        properties: [...properties, ...generated.values()],
        functions: Array.isArray(metadata.functions) ? metadata.functions : [],
        events: Array.isArray(metadata.events) ? metadata.events : [],
        tags: Array.isArray(metadata.tags) ? metadata.tags : [],
    }
}

export const getReadablePropertyIds = (rows: RegisterMappingRow[]) => rows
    .filter(row => row.propertyId && READ_FUNCTION_CODES.includes(Number(row.functionCode)))
    .map(row => row.propertyId)
