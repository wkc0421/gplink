<template>
  <main style="max-width:1100px;margin:24px auto">
    <a-space>
      <a-button @click="open = true">从采集项库添加</a-button>
      <a-button @click="complete = !complete">{{ complete ? '精简字段' : '完整字段' }}</a-button>
      <a-button @click="save">保存草稿</a-button>
      <a-button @click="failSave = !failSave">{{ failSave ? '恢复保存' : '模拟保存失败' }}</a-button>
      <a-button @click="rows[1].expands.source = undefined">清空隐藏来源</a-button>
    </a-space>
    <p role="status">{{ status }}</p>
    <EditTable ref="table" :data-source="rows" :columns="visibleColumns" :validation-columns="columns" :should-validate-row="hasPropertyDraftContent" :height="280" :disable-menu="false">
      <template #id="{ record, index }"><FormItem :name="[index, 'id']"><LibraryItemInput v-model:value="record.id" field="id" :library-id="libraryId" :existing-codes="otherCodes(record)" :disabled="record.expands.isProduct" @select="pick(record, $event)" /></FormItem></template>
      <template #name="{ record, index }"><FormItem :name="[index, 'name']"><LibraryItemInput v-model:value="record.name" field="name" :library-id="libraryId" :existing-codes="otherCodes(record)" :disabled="record.expands.isProduct" @select="pick(record, $event)" /></FormItem></template>
      <template #source="{ record, index }"><FormItem :name="[index, 'expands', 'source']"><a-input v-model:value="record.expands.source" aria-label="来源" /></FormItem></template>
    </EditTable>
    <LibraryPicker v-model:open="open" v-model:library-id="libraryId" :existing-codes="rows.map(row => row.id).filter(Boolean)" @add="add" />
    <pre data-testid="draft">{{ JSON.stringify(rows.map(row => ({ id:row.id, name:row.name, expands:row.expands, valueType:row.valueType }))) }}</pre>
    <pre data-testid="saved">{{ JSON.stringify(saved) }}</pre>
  </main>
</template>
<script setup lang="ts">
import { computed, ref } from 'vue'
import EditTable from '../../src/modules/device-manager-ui/components/Metadata/Table.vue'
import FormItem from '../../src/modules/device-manager-ui/components/Metadata/TableFormItem.vue'
import LibraryPicker from '../../src/modules/device-manager-ui/components/PropertyLibrary/LibraryPicker.vue'
import LibraryItemInput from '../../src/modules/device-manager-ui/components/PropertyLibrary/LibraryItemInput.vue'
import { applyPropertyLibraryItem, hasPropertyDraftContent, propertyDisplayColumns, uniquePropertyLibraryItems } from '../../src/modules/device-manager-ui/components/PropertyLibrary/metadataCatalog'
const blank = () => ({ id: '', name: '', valueType: { type: undefined, expands: {} }, expands: { source: 'device', type: ['report'], registerAddr: undefined } })
const rows = ref<any[]>([{ ...blank(), id: 'Ua', name: '已有A相电压', expands: { isProduct:true, source:'device',type:['report'], registerAddr:'42' } }, blank(), blank()])
const libraryId = ref('standard'), open = ref(false), complete = ref(false), status = ref(''), failSave = ref(false), saved = ref<any[]>([]), table = ref()
const columns = [
  { title:'ID',dataIndex:'id',form:{rules:[{required:true,message:'ID 必填'}]} },
  { title:'名称',dataIndex:'name',form:{rules:[{required:true,message:'名称必填'}]} },
  { title:'来源',dataIndex:'source',form:{field:['expands','source'],rules:[{required:true,message:'来源必填'}]} },
]
const visibleColumns = computed(() => propertyDisplayColumns(columns, complete.value))
const otherCodes = (row: any) => rows.value.filter(other => other !== row).map(item => item.id).filter(Boolean)
function pick(row:any,item:any) { try { applyPropertyLibraryItem(row,item,rows.value) } catch (error:any) {status.value=error.message} }
function add(items:any[]) { const {accepted}=uniquePropertyLibraryItems(items,rows.value); rows.value.splice(1,0,...accepted.map(item=>({...blank(),id:item.code,name:item.name}))) }
async function save() {
 try {
  const result=await table.value.validate()
  if(failSave.value) throw new Error('保存失败，草稿保留')
  saved.value=result; status.value='保存成功'
 } catch(error:any) { status.value=error.message || error.flat().map((e:any)=>e.message).join('；') }
}
</script>
