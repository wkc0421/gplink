import { h, reactive } from 'vue'
import { defineStore } from 'pinia'
import { createI18n } from 'vue-i18n'
import deviceMessages from '../../src/modules/device-manager-ui/locales/lang/zh.json'
export default createI18n({ legacy: false, locale: 'zh', missingWarn: false, fallbackWarn: false, messages: { zh: {
  ...deviceMessages,
  'Base.Base.640395-6': '保存物模型', 'Base.Base.640395-9': 'ID 必填', 'Base.Base.640395-17': '保存成功',
  'Base.columns.448718-21': '请选择数据类型', 'Base.columns.448718-24': '请选择来源',
  'Base.columns.448718-15': 'ID 重复',
} } })
export const AIcon = { render: () => h('span') }
export const randomString = () => Math.random().toString(36).slice(2)
export const randomNumber = () => Date.now()
export const isFullScreen = () => false
export const onlyMessage = (text:string) => { testState.message=text }
export const getToken = () => ''
export const EventEmitter = { subscribe() {}, unSubscribe() {} }
export const testState = reactive({ failSave: false, message: '', lastPayload: null as any, lastTarget: '' })
const initialMetadata=JSON.stringify({properties:[{id:'Ua',name:'原A相电压',valueType:{type:'float',unit:'V'},expands:{source:'device',type:['report'],registerAddr:'42',groupId:'g1',groupName:'默认分组'}}],functions:[{id:'originalFunction'}],events:[],tags:[]})
export const useProductStore=defineStore('test-product',{state:()=>({current:{id:'test-product',metadata:initialMetadata}}),getters:{detail:state=>state.current},actions:{setCurrent(value:any){this.current=value}}})
export const useInstanceStore=defineStore('test-instance',{state:()=>({current:{id:'test-device',productId:'test-product',metadata:initialMetadata,productMetadata:initialMetadata,features:[] as any[]}}),getters:{detail:state=>state.current},actions:{setCurrent(value:any){this.current=value}}})
export const useAuthStore=defineStore('test-auth',{state:()=>({canUpdate:true}),actions:{hasPermission(){return this.canUpdate}}})
export const useSystemStore=defineStore('test-system',{state:()=>({layout:{openKeys:[]}})})
export const useMenuStore=defineStore('test-menu',{actions:{jumpPage(){}}})
const response = async (method: string, url: string, data: any = {}) => {
  const target = method === 'GET' ? `${url}?${new URLSearchParams(data)}` : url
  const result = await fetch(`/mock${target}`, { method, headers: { 'Content-Type': 'application/json' }, ...(method === 'GET' ? {} : { body: JSON.stringify(data) }) })
  return result.json()
}
export const request = {
  get: (url: string, params?: any) => response('GET', url, params),
  post: (url: string, body?: any) => response('POST', url, body),
  put: (url: string, body?: any) => response('PUT', url, body),
  remove: (url: string) => response('DELETE', url),
}
