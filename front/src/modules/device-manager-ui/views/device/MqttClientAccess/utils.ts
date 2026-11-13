export const validAccessId = (id: string) => /^(?=.*[a-zA-Z])(?=.*\d)[a-zA-Z\d]{2,32}$/.test(id)

export const defaultNames = (id: string) => ({
  networkName: `MQTT客户端_${id}`,
  username: `mqtt_user_${id}`,
  password: `mqtt_pwd_${id}`,
  clientId: `gplink_${id}`,
  accessName: `MQTT网关_${id}`,
  protocolName: `MQTT协议_${id}`,
  productName: `MQTT产品_${id}`,
})

export function updateDefaults<T extends Record<string, string>>(values: T, oldDefaults: T, next: T) {
  for (const key of Object.keys(next) as (keyof T)[]) {
    if (values[key] === oldDefaults[key]) values[key] = next[key]
  }
}

export type ImportEvent = {
  type: 'row' | 'detailFile' | 'complete' | 'legacyProgress'
  success: boolean
  row?: number
  deviceId?: string
  message?: string
  detailFile?: string
  successCount?: number
  failureCount?: number
}

const isCount = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value) && value >= 0

function parseImportEvent(payload: any): ImportEvent {
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('导入接口返回了无法识别的数据')
  // Some deployments wrap SSE data in the same envelope as ordinary HTTP responses.
  if (typeof payload.status === 'number') {
    if (payload.status !== 200 || !payload.result) throw new Error(payload.message || `导入请求失败（${payload.status}）`)
    payload = payload.result
  }
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('导入接口返回了无法识别的数据')
  if (payload.type === 'row' && typeof payload.success === 'boolean') return payload
  if (payload.type === 'detailFile' && typeof payload.detailFile === 'string') return payload
  if (payload.type === 'complete' && isCount(payload.successCount) && isCount(payload.failureCount)) return payload
  // Legacy streams report saved batch totals and a separate result-file event, without a completion marker.
  // Keep reading these events so an early result-file event does not cancel the remaining import.
  if (!payload.type && typeof payload.success === 'boolean') {
    if (typeof payload.detailFile === 'string' && payload.detailFile) {
      return { type: 'legacyProgress', success: true, detailFile: payload.detailFile }
    }
    if (payload.success && isCount(payload.result?.total)) {
      return { type: 'legacyProgress', success: true, successCount: payload.result.total }
    }
    if (!payload.success && typeof payload.message === 'string' && payload.message) {
      return { type: 'legacyProgress', success: false, failureCount: 1, message: payload.message, row: payload.row }
    }
  }
  throw new Error(payload.message || '导入接口未返回有效结果，请确认后端已更新')
}

/** One fetch, no automatic reconnection: reconnecting would execute the import again. */
export async function consumeImportStream(response: Response, receive: (event: ImportEvent) => void) {
  if (!response.ok) throw new Error(response.status === 403 ? '没有设备导入权限' : `导入请求失败（${response.status}）`)
  if (!response.headers.get('content-type')?.includes('text/event-stream')) throw new Error('服务器未返回导入事件流')
  const reader = response.body?.getReader()
  if (!reader) throw new Error('浏览器不支持读取导入结果')
  const decoder = new TextDecoder()
  let buffer = ''
  let completed = false
  let legacy = false
  function dispatch(frame: string) {
    if (completed) return
    const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
    if (!data) return
    const event = parseImportEvent(JSON.parse(data))
    if (event.type === 'legacyProgress') legacy = true
    if (event.type === 'complete') completed = true
    receive(event)
  }
  try {
    while (!completed) {
      const { done, value } = await reader.read()
      buffer += decoder.decode(value, { stream: !done })
      buffer = buffer.replace(/\r\n/g, '\n')
      let end: number
      while ((end = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, end)
        buffer = buffer.slice(end + 2)
        dispatch(frame)
      }
      if (done) {
        if (buffer.trim()) dispatch(buffer)
        break
      }
    }
    if (!completed && !legacy) throw new Error('导入连接中断，已成功的数据保留；请核对结果后仅重传失败行')
    return completed ? 'complete' : 'legacy'
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}
