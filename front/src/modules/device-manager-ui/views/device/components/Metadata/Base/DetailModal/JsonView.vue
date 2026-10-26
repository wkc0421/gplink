<template>
  <div class="json-view-box">
    <div class="json-view-border">
      <j-scrollbar :height="height">
        <JsonViewer :value="value" :expanded="true" :expandDepth="4" theme="dark" />
      </j-scrollbar>
    </div>

  </div>
</template>

<script setup lang="ts" name="JsonView">
import 'vue3-json-viewer/dist/index.css';
import { JsonViewer } from 'vue3-json-viewer';

const props = defineProps({
  value: {
    type: [Object, Array],
    default: () => ({})
  },
  height: {
    type: Number,
    default: 250
  }
})

</script>

<style scoped lang="less">
.json-view-box {
  width: 100%;
  padding-left: 32px;
  .json-view-border {
    border: 1px solid var(--app-border);
    border-radius:2px;
  }

  :deep(.jv-container.jv-dark) {
    background: var(--app-elevated);
  }
}
</style>
