import { request } from '@jetlinks-web/core'

export const queryScheduledTasks = (data: any) =>
  request.post('/scheduled-task/_query', data)

export const getScheduledTask = (id: string) =>
  request.get(`/scheduled-task/${id}`)

export const saveScheduledTask = (data: any) =>
  data.id
    ? request.put(`/scheduled-task/${data.id}`, data)
    : request.post('/scheduled-task', data)

export const deleteScheduledTask = (id: string) =>
  request.remove(`/scheduled-task/${id}`)

export const enableTask = (id: string) =>
  request.put(`/scheduled-task/${id}/_enable`, {})

export const disableTask = (id: string) =>
  request.put(`/scheduled-task/${id}/_disable`, {})

export const enableLog = (id: string) =>
  request.put(`/scheduled-task/${id}/_log/enable`, {})

export const disableLog = (id: string) =>
  request.put(`/scheduled-task/${id}/_log/disable`, {})

export const queryLogs = (taskId: string, data: any) =>
  request.post(`/scheduled-task/${taskId}/logs/_query`, data)

export const deleteLogs = (taskId: string) =>
  request.remove(`/scheduled-task/${taskId}/logs`)

// Reuse existing product/device endpoints
export const getProductListNoPaging = (data?: any) =>
  request.post('/device-product/_query/no-paging', {
    paging: false,
    sorts: [{ name: 'name', order: 'asc' }],
    ...data,
  })

export const getProductMetadata = (productId: string) =>
  request.get(`/device-product/${productId}/config-metadata`)

export const getDevicesByProduct = (productId: string) =>
  request.post('/device-instance/_query/no-paging', {
    paging: false,
    sorts: [{ name: 'name', order: 'asc' }],
    terms: [{ column: 'productId', value: productId }],
  })
