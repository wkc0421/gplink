import { expect, test } from '@playwright/test'
import { consumeImportStream, defaultNames, updateDefaults, validAccessId } from '../../src/modules/device-manager-ui/views/device/MqttClientAccess/utils'

test('ID and custom defaults preserve credentials and case', () => {
  for (const id of ['a1', 'ABC123', 'a'.repeat(31) + '1']) expect(validAccessId(id)).toBe(true)
  for (const id of ['', 'a', '123', 'abc', 'ab-1', ' ab1', '中文1', 'a'.repeat(32) + '1']) expect(validAccessId(id)).toBe(false)
  const values = defaultNames('abc123')
  values.password = 'custom'; values.username = ''
  updateDefaults(values, defaultNames('abc123'), defaultNames('abc456'))
  expect(values.password).toBe('custom'); expect(values.username).toBe('')
  expect(values.clientId).toBe('gplink_abc456')
})

test('SSE handles split UTF8, CRLF, multiline JSON and final summary', async () => {
  const content = 'data: {"type":"row",\r\ndata: "success":false,"message":"设备已存在"}\r\n\r\ndata: {"type":"complete","successCount":0,"failureCount":1}\r\n\r\n'
  const bytes = new TextEncoder().encode(content)
  const stream = new ReadableStream({ start(controller) { for (const byte of bytes) controller.enqueue(new Uint8Array([byte])); controller.close() } })
  const events: any[] = []
  await consumeImportStream(new Response(stream, { headers: { 'content-type': 'text/event-stream' } }), event => events.push(event))
  expect(events).toHaveLength(2); expect(events[0].message).toBe('设备已存在')
  expect(events[1].failureCount).toBe(1)
})

test('EOF without completion is interrupted even after successful rows', async () => {
  const events: any[] = []
  await expect(consumeImportStream(new Response('data: {"type":"row","success":true}\n\n', { headers: { 'content-type': 'text/event-stream' } }), event => events.push(event))).rejects.toThrow('中断')
  expect(events).toHaveLength(1)
})

test('forbidden and incompatible responses cannot report success', async () => {
  await expect(consumeImportStream(new Response('', { status: 403 }), () => {})).rejects.toThrow('权限')
  await expect(consumeImportStream(new Response('{}', { headers: { 'content-type': 'application/json' } }), () => {})).rejects.toThrow('事件流')
  await expect(consumeImportStream(new Response('data: {"success":true}\n\n', { headers: { 'content-type': 'text/event-stream' } }), () => {})).rejects.toThrow('后端已更新')
})

test('legacy detail-file event does not cancel later saved batch totals', async () => {
  const events: any[] = []
  const content = [
    { success: false, detailFile: '/file/result.xlsx' },
    { success: true, result: { added: 0, updated: 1, total: 1 } },
    { success: true, result: { added: 2, updated: 0, total: 2 } },
    { success: false, message: '第5行：设备ID已存在' },
  ].map(event => `data: ${JSON.stringify(event)}\n\n`).join('')
  const result = await consumeImportStream(new Response(content, { headers: { 'content-type': 'text/event-stream' } }), event => events.push(event))
  expect(result).toBe('legacy')
  expect(events).toHaveLength(4)
  expect(events[0].detailFile).toBe('/file/result.xlsx')
  expect(events.reduce((count, event) => count + (event.successCount || 0), 0)).toBe(3)
  expect(events.reduce((count, event) => count + (event.failureCount || 0), 0)).toBe(1)
  expect(events.some(event => event.type === 'complete')).toBe(false)
})

test('wrapped SSE preserves typed row failures and authoritative completion', async () => {
  const events: any[] = []
  const content = [
    { type: 'row', success: true, row: 2, deviceId: 'new1' },
    { type: 'row', success: false, row: 3, message: '设备已存在' },
    { type: 'detailFile', success: true, detailFile: '/file/result.csv' },
    { type: 'complete', success: false, successCount: 1, failureCount: 1 },
  ].map(result => `data: ${JSON.stringify({ status: 200, result })}\n\n`).join('')
  await expect(consumeImportStream(new Response(content, { headers: { 'content-type': 'text/event-stream' } }), event => events.push(event))).resolves.toBe('complete')
  expect(events).toHaveLength(4)
  expect(events[1].success).toBe(false)
  expect(events[3].successCount).toBe(1)
  expect(events[3].failureCount).toBe(1)
})

test('response-envelope errors and invalid completion counts are not successful imports', async () => {
  for (const payload of [
    { status: 403, message: '无导入权限' },
    { type: 'complete', successCount: -1, failureCount: 0 },
    { type: 'complete', successCount: '1', failureCount: 0 },
  ]) {
    const events: any[] = []
    await expect(consumeImportStream(new Response(`data: ${JSON.stringify(payload)}\n\n`, { headers: { 'content-type': 'text/event-stream' } }), event => events.push(event))).rejects.toThrow()
    expect(events).toEqual([])
  }
})

test('network failure preserves legacy processed counts without inventing completion', async () => {
  let pulls = 0
  const events: any[] = []
  const stream = new ReadableStream({
    pull(controller) {
      if (pulls++ === 0) controller.enqueue(new TextEncoder().encode('data: {"success":true,"result":{"total":1}}\n\n'))
      else controller.error(new Error('connection reset'))
    },
  })
  await expect(consumeImportStream(new Response(stream, { headers: { 'content-type': 'text/event-stream' } }), event => events.push(event))).rejects.toThrow('connection reset')
  expect(events).toEqual([{ type: 'legacyProgress', success: true, successCount: 1 }])
})
