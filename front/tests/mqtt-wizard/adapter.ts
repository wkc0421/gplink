export const BASE_API = '/api', TOKEN_KEY = 'X-Access-Token', TOKEN_KEY_URL = 'token'
export const getToken = () => 'test-token'
export const LocalStore = { get: getToken }
export const isNoCommunity = false
export const ndJson = () => {}
const call = async (method: string, url: string, body?: any, config?: any) => {
  const params = config?.params ? `?${new URLSearchParams(config.params)}` : ''
  const response = await fetch(BASE_API + url + params, { method, headers: { 'Content-Type': 'application/json' }, ...(method !== 'GET' ? { body: JSON.stringify(body) } : {}) })
  if (config?.responseType === 'blob') return response.blob()
  return response.json()
}
export const request = {
  get: <T>(url: string, data?: any, config?: any) => call('GET', url, data, config),
  post: <T>(url: string, data?: any, config?: any) => call('POST', url, data, config),
  patch: <T>(url: string, data?: any) => call('PATCH', url, data),
  put: <T>(url: string, data?: any) => call('PUT', url, data),
  remove: <T>(url: string, data?: any) => call('DELETE', url, data),
}
