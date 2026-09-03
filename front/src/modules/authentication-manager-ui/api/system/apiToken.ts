import { request } from '@jetlinks-web/core'

export const queryApiTokens = (data: any) => request.post('/api-token/_query', data)
export const createApiToken = (data: any) => request.post('/api-token', data)
export const getApiToken = (id: string) => request.get(`/api-token/${id}`)
export const getApiTokenGrantOptions = () => request.get('/api-token/grant-options')
export const rotateApiToken = (id: string, data?: any) => request.post(`/api-token/${id}/rotate`, data)
export const revokeApiToken = (id: string) => request.post(`/api-token/${id}/revoke`)
export const queryApiTokenAudit = (id: string) => request.get(`/api-token/${id}/audit`)

export const queryApiTokenProducts = (data: any) =>
  request.post('/device-product/_query/no-paging?paging=false', data)

export const queryApiTokenDevices = (data: any) =>
  request.post('/device-instance/_query/no-paging?paging=false', data)
