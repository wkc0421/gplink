import { request } from '@jetlinks-web/core'

export const saveModbusAccess = (data: any) => request.post('/device/modbus/access/_save', data)
