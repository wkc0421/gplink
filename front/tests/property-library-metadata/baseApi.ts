import { testState } from './mock'
export const getUnit = async () => ({ success:true, result:[] })
export const getMetadataConfig = async () => ({ success:true, result:[] })
export const getMetadataDeviceConfig = getMetadataConfig
export const exportProductPropertyMetadata = async (id:string) => {
  const response = await fetch(`/mock/v1/product/${encodeURIComponent(id)}/property-metadata/properties.xlsx`)
  const body = await response.blob()
  if (!response.ok) throw { response: { data: body } }
  return body
}
export const saveProductVirtualProperty = async () => ({ success:true })
export const saveDeviceVirtualProperty = saveProductVirtualProperty
export const saveProductMetadata = async (payload:any) => {
  testState.lastPayload=JSON.parse(JSON.stringify(payload));testState.lastTarget='product'
  return {success:!testState.failSave,message:testState.failSave?'保存失败，草稿保留':''}
}
export const saveMetadata = async (id:string,payload:any) => {
  testState.lastPayload=JSON.parse(JSON.stringify(payload));testState.lastTarget='device'
  return {success:!testState.failSave,message:testState.failSave?'保存失败，草稿保留':''}
}
