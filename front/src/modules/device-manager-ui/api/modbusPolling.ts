import { request } from '@jetlinks-web/core'

export interface ModbusPollingReloadStatus {
  requestedRevision: number
  appliedRevision: number
  reloading: boolean
  activeGatewayCount: number
}

export const getModbusPollingStatus = () =>
  request.get<ModbusPollingReloadStatus>('/modbus/polling/status')

export const waitForModbusPollingApplied = async (
  previousRevision?: number,
  timeoutMs = 5000,
) => {
  const deadline = Date.now() + timeoutMs
  const baseline = Number.isFinite(previousRevision) ? previousRevision : undefined
  while (Date.now() <= deadline) {
    try {
      const response: any = await getModbusPollingStatus()
      const status = response?.result || response
      const requested = Number(status?.requestedRevision || 0)
      const applied = Number(status?.appliedRevision || 0)
      const observed = baseline === undefined || requested > baseline
      if (observed && !status?.reloading && requested === applied) {
        return true
      }
    } catch {
      return false
    }
    await new Promise(resolve => setTimeout(resolve, 150))
  }
  return false
}
