<template>
  <main style="max-width:1250px;margin:24px auto">
    <a-space><a-button @click="testState.failSave = !testState.failSave">{{ testState.failSave ? '恢复保存' : '模拟保存失败' }}</a-button><a-button @click="auth.canUpdate = !auth.canUpdate">切换编辑权限</a-button></a-space>
    <a-space v-if="missingMetadata"><a-button @click="changeProductMetadata">更新产品物模型</a-button><a-button @click="instance.current.metadata = '{}'">设置空的已合并物模型</a-button></a-space>
    <p role="status">{{ testState.message }}</p>
    <Base :target="target" :type="metadataType" :permission="target === 'product' ? 'device/Product' : 'device/Instance'" />
    <pre data-testid="store">{{ target === 'product' ? product.current.metadata : instance.current.metadata }}</pre>
    <pre data-testid="payload">{{ JSON.stringify(testState.lastPayload) }}</pre>
    <p data-testid="target">{{ testState.lastTarget }}</p>
  </main>
</template>
<script setup lang="ts">
import Base from '../../src/modules/device-manager-ui/views/device/components/Metadata/Base/Base.vue'
import { testState, useProductStore, useInstanceStore, useAuthStore } from './mock'
const target=new URLSearchParams(location.search).get('real')==='device'?'device':'product'
const metadataType=(new URLSearchParams(location.search).get('type') || 'properties') as 'properties' | 'functions' | 'events' | 'tags'
const product=useProductStore(),instance=useInstanceStore(),auth=useAuthStore()
if (new URLSearchParams(location.search).get('definitionSeed') === '1') {
  const model = {
    properties: [],
    functions: [{ id: 'reboot', name: '重启', async: false, inputs: [{ id: 'delay', name: '延时', valueType: { type: 'int', unit: 's' } }], output: { type: 'string', maxLength: 32, expands: { keep: 'output-extra' } }, description: '功能描述', expands: { keep: 'function-extra' } }],
    events: [{ id: 'alarm', name: '告警', valueType: { type: 'object', properties: [{ id: 'level', name: '级别', valueType: { type: 'int' } }] }, description: '事件描述', expands: { level: 'warn', keep: 'event-extra' } }],
    tags: [{ id: 'location', name: '位置', valueType: { type: 'string' }, description: '标签描述', expands: { keep: 'tag-extra' } }],
  }
  if (new URLSearchParams(location.search).get('definitionPageSeed') === '1') {
    for (let i = 0; i < 11; i++) model.tags.push({ id: `tag_${i}`, name: `标签${i}`, valueType: i === 10 ? { type: 'enum', elements: [] } as any : { type: 'string' }, description: '', expands: { keep: 'paged' } })
  }
  product.current.metadata = JSON.stringify(model)
  instance.current.metadata = JSON.stringify(model)
  instance.current.productMetadata = JSON.stringify(model)
  const eventLimit = new URLSearchParams(location.search).get('eventLimit')
  if (eventLimit) {
    instance.current.productMetadata = JSON.stringify({ ...model, events: [] })
    instance.current.features = [{ id: eventLimit, name: eventLimit }]
  }
}
if (new URLSearchParams(location.search).get('exportSeed') === '1') {
  const model = JSON.parse(product.current.metadata)
  for (let i = 0; i < 12; i++) model.properties.push({ id: `Export${i}`, name: `导出属性${i}`, valueType: { type: 'float' }, expands: { source: 'device', type: ['report'], groupId: 'g1', groupName: '默认分组' } })
  product.current.metadata = JSON.stringify(model)
}
if (new URLSearchParams(location.search).get('batchSeed') === '1') {
  const model = JSON.parse(product.current.metadata)
  model.properties.push({ id: 'Enum1', name: '枚举测试', valueType: { type: 'enum', unit: 'V', elements: [{ text: '开', value: 'on' }] }, expands: { source: 'device', type: ['report'], registerAddr: '77', metrics: [{ id: 'keep' }], groupId: 'g1', groupName: '默认分组' } })
  model.properties.push({ id: 'Other1', name: '其他组属性', valueType: { type: 'float', unit: 'A' }, expands: { source: 'device', type: ['report'], registerAddr: '88', groupId: 'g2', groupName: '第二分组' } })
  product.current.metadata = JSON.stringify(model)
}
const missingMetadata = new URLSearchParams(location.search).get('missingMetadata') === '1'
if (missingMetadata) instance.current.metadata = ''
const changeProductMetadata = () => {
  const metadata = JSON.parse(instance.current.productMetadata)
  metadata.properties[0].name = '更新后的继承属性'
  instance.current.productMetadata = JSON.stringify(metadata)
}
</script>
