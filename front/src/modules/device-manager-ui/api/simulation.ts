import { request } from '@jetlinks-web/core'

export interface SimulationRequest {
  deviceId: string; propertyId: string; requestId: string
  startTime: number; endTime: number; intervalMinutes: number
  mode: 'RANDOM' | 'INCREMENT'; min?: string; max?: string; initial?: string; increment?: string; snapshots: boolean
}
export interface SimulationProperty {
  propertyId: string; name: string; type: string; unit: string; scale: number
  lower?: string; upper?: string; snapshotEligible: boolean
}
export interface SimulationPreview {
  count: number; existing: number; oldestTime: number; rawExists: boolean
  layers: Record<string, string>; storageReady: boolean; canInitialize: boolean
  snapshotEnabled: boolean; productId: string; firstValue: string; lastValue: string
}
export interface SimulationTask {
  id: string; status: string; stage: string; cursor: number; total: number
  refresh_cursor: number; layers: Record<string, string>; snapshotStatus: string; error?: string; request: SimulationRequest; createdAt: string; updatedAt: string
}
const base = '/device-data/simulation'
export const simulationProperties = (id: string) => request.get<SimulationProperty[]>(`${base}/devices/${encodeURIComponent(id)}/properties`)
export const previewSimulation = (body: SimulationRequest) => request.post<SimulationPreview>(`${base}/_preview`, body)
export const submitSimulation = (body: SimulationRequest) => request.post<SimulationTask>(`${base}/tasks`, body)
export const simulationTasks = (pageIndex: number) => request.get<SimulationTask[]>(`${base}/tasks`, { pageIndex, pageSize: 20 })
export const retrySimulation = (id: string) => request.post<SimulationTask>(`${base}/tasks/${encodeURIComponent(id)}/_retry`)
export const simulationTask = (id: string) => request.get<SimulationTask>(`${base}/tasks/${encodeURIComponent(id)}`)
