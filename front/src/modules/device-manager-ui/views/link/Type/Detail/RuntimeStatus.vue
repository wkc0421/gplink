<template>
  <a-card class="runtime-status" size="small" :title="t('Type.runtime.title')">
    <template #extra>
      <a-space wrap>
        <span v-if="updatedAt" class="updated-at">{{ t('Type.runtime.updatedAt', [updatedAt]) }}</span>
        <a-button size="small" :loading="loading" @click="refresh">
          <template #icon><AIcon type="ReloadOutlined" /></template>
          {{ t('Type.runtime.refresh') }}
        </a-button>
      </a-space>
    </template>
    <a-alert v-if="error" type="error" show-icon :message="t('Type.runtime.failed')" />
    <a-skeleton v-else-if="!runtime" active :paragraph="{ rows: 2 }" />
    <template v-else>
      <a-descriptions size="small" :column="{ xs: 1, sm: 2 }">
        <a-descriptions-item :label="t('Type.runtime.node')">{{ runtime.serverId }}</a-descriptions-item>
        <a-descriptions-item :label="t('Type.runtime.configState')">
          {{ runtime.configState ? t('Type.runtime.' + runtime.configState) : '—' }}
        </a-descriptions-item>
        <a-descriptions-item :label="t('Type.runtime.instance')">
          <a-badge :status="runtime.alive ? 'success' : 'default'" :text="instanceState" />
        </a-descriptions-item>
        <a-descriptions-item v-if="details.connected != null || details.connecting" :label="t('Type.runtime.connectionState')">
          <a-badge :status="details.connected ? 'success' : details.connecting ? 'processing' : 'default'" :text="connectionState" />
        </a-descriptions-item>
        <a-descriptions-item v-if="details.listening != null" :label="t('Type.runtime.listener')">
          {{ t(details.listening ? 'Type.runtime.listening' : 'Type.runtime.notListening') }}
        </a-descriptions-item>
        <a-descriptions-item v-for="key in visibleFields" :key="key" :label="t('Type.runtime.' + key)">
          {{ details[key] }}
        </a-descriptions-item>
      </a-descriptions>
      <template v-if="details.connections">
        <div v-if="!details.connections.length" class="connection-note">{{ t('Type.runtime.noClients') }}</div>
        <div v-else>
          <div v-if="details.connectionCount != null && details.connections.length < details.connectionCount" class="connection-note">
            {{ t('Type.runtime.partialClients', [details.connections.length, details.connectionCount]) }}
          </div>
          <a-table
            size="small"
            :columns="columns"
            :data-source="details.connections"
            :row-key="connectionKey"
            :pagination="{ pageSize: 10, hideOnSinglePage: true, showSizeChanger: false }"
          />
        </div>
      </template>
    </template>
  </a-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useI18n } from 'vue-i18n';
import { getNetworkRuntime, type NetworkRuntime } from '../../../../api/link/type';

const props = defineProps<{ id: string }>();
const { t, locale } = useI18n();
const runtime = ref<NetworkRuntime>();
const loading = ref(false);
const error = ref(false);
const updatedAt = ref('');
const details = computed(() => runtime.value?.details ?? {});
const fields = ['clientId', 'remoteAddress', 'localAddress', 'bindAddress', 'connectionCount'] as const;
const visibleFields = computed(() => fields.filter(key => details.value[key] != null));
const instanceState = computed(() => t(!runtime.value?.available ? 'Type.runtime.noInstance'
  : runtime.value.alive ? 'Type.runtime.active' : 'Type.runtime.inactive'));
const connectionState = computed(() => t(details.value.connected ? 'Type.runtime.connected'
  : details.value.connecting ? 'Type.runtime.connecting' : 'Type.runtime.disconnected'));
const columns = computed(() => {
  const keys = runtime.value?.type === 'MQTT_SERVER' ? ['clientId', 'remoteHost', 'remotePort'] : ['remoteHost', 'remotePort'];
  return keys.map(key => ({ title: t('Type.runtime.' + key), dataIndex: key }));
});
const connectionKey = (row: NonNullable<NetworkRuntime['details']['connections']>[number]) =>
  JSON.stringify([row.remoteHost, row.remotePort, row.clientId]);

async function refresh() {
  if (loading.value) return;
  loading.value = true;
  error.value = false;
  try {
    const response = await getNetworkRuntime(props.id);
    if (response.status !== 200 || !response.result) throw new Error();
    runtime.value = response.result;
    updatedAt.value = new Date().toLocaleTimeString(locale.value);
  } catch {
    runtime.value = undefined;
    updatedAt.value = '';
    error.value = true;
  } finally {
    loading.value = false;
  }
}

onMounted(refresh);
</script>

<style scoped>
.runtime-status { margin-bottom: 24px; }
.updated-at, .connection-note { color: var(--app-text-secondary); }
.connection-note { margin: 4px 0 8px; }
</style>
