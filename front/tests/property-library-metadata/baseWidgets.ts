import { h } from 'vue'
import { Form } from 'ant-design-vue'
export { default as EditTable } from '../../src/modules/device-manager-ui/components/Metadata/Table.vue'
export { default as EditTableFormItem } from '../../src/modules/device-manager-ui/components/Metadata/TableFormItem.vue'
const unused={render:()=>null}
export const TypeSelect={props:['value','disabled'],emits:['update:value','valueChange'],setup(props:any,{emit}:any){const form=Form.useInjectFormItemContext();return()=>h('select',{'aria-label':'数据类型',disabled:props.disabled,value:props.value?.type||'',onChange:(event:any)=>{emit('update:value',{...props.value,type:event.target.value||undefined});emit('valueChange');form.onFieldChange()}},[h('option',{value:''},'选择类型'),h('option',{value:'float'},'float'),h('option',{value:'int'},'int'),h('option',{value:'enum'},'enum'),h('option',{value:'object'},'object')])}}
export const UnitSelect={props:['value','disabled'],emits:['update:value'],setup(props:any,{emit}:any){return()=>h('input',{'aria-label':'单位',disabled:props.disabled,value:props.value,onInput:(e:any)=>emit('update:value',e.target.value)})}}
export const Source={props:['value','disabled'],emits:['update:value'],setup(props:any,{emit}:any){const form=Form.useInjectFormItemContext();return()=>h('select',{'aria-label':'来源',disabled:props.disabled,value:props.value?.source||'',onChange:(e:any)=>{emit('update:value',{...props.value,source:e.target.value||undefined});form.onFieldChange()}},[h('option',{value:''},'请选择'),h('option',{value:'device'},'设备')])}}
export const OtherSetting=unused, PropertiesModal=unused, FunctionModal=unused, EventModal=unused, TagsModal=unused, Import=unused
export const IntegerParams=unused,StringParams=unused,DateParams=unused,FileParams=unused,EnumParams=unused,BooleanParams=unused,ObjectParams=unused,ArrayParams=unused,DoubleParams=unused,GroupSelect=unused,BooleanSelect=unused
export const EventLevel=[]
