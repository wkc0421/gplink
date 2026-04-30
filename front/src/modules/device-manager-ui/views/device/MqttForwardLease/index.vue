<template>
    <j-page-container>
        <FullPage :fixed="false">
            <div class="mqtt-forward-lease-page">
                <div class="toolbar">
                    <a-space>
                        <a-button type="primary" @click="openCreate">
                            <template #icon><AIcon type="PlusOutlined" /></template>
                            新增设备订阅
                        </a-button>
                        <a-button :loading="loading" @click="loadActive">
                            <template #icon><AIcon type="ReloadOutlined" /></template>
                            刷新
                        </a-button>
                    </a-space>
                    <span class="toolbar-tip">
                        页面每 10 秒自动刷新一次，lease TTL 为 {{ activeInfo.ttlSeconds || 180 }} 秒。
                    </span>
                </div>

                <div class="summary-grid">
                    <div class="summary-item">
                        <div class="summary-label">活跃 Lease</div>
                        <div class="summary-value">{{ activeInfo.leaseCount || 0 }}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">设备 EventBus 订阅</div>
                        <div class="summary-value">{{ activeInfo.deviceSubscriptionCount || 0 }}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">索引项</div>
                        <div class="summary-value">{{ activeInfo.indexEntryCount || 0 }}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">已产生转发的 Lease</div>
                        <div class="summary-value">{{ forwardedLeaseCount }}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">转发总次数</div>
                        <div class="summary-value">{{ totalForwardCount }}</div>
                    </div>
                    <div class="summary-item">
                        <div class="summary-label">最近转发</div>
                        <div class="summary-value small">{{ formatTime(lastForwardTime) }}</div>
                    </div>
                </div>

                <a-table
                    row-key="leaseId"
                    size="middle"
                    :columns="columns"
                    :data-source="leases"
                    :loading="loading"
                    :pagination="{ pageSize: 10, showSizeChanger: true }"
                    :scroll="{ x: 1180 }"
                >
                    <template #bodyCell="{ column, record }">
                        <template v-if="column.key === 'leaseId'">
                            <div class="mono strong">{{ record.leaseId }}</div>
                            <div class="muted">创建：{{ formatTime(record.createdAt) }}</div>
                        </template>
                        <template v-else-if="column.key === 'devices'">
                            <a-space wrap>
                                <a-tag v-for="deviceId in previewItems(record.deviceIds)" :key="deviceId">
                                    {{ deviceId }}
                                </a-tag>
                                <a-tag v-if="record.deviceIds.length > 3">+{{ record.deviceIds.length - 3 }}</a-tag>
                            </a-space>
                        </template>
                        <template v-else-if="column.key === 'properties'">
                            <a-tag v-if="!record.watchedProperties?.length" color="blue">全部采集项</a-tag>
                            <a-space v-else wrap>
                                <a-tag v-for="property in previewItems(record.watchedProperties)" :key="property">
                                    {{ property }}
                                </a-tag>
                                <a-tag v-if="record.watchedProperties.length > 3">
                                    +{{ record.watchedProperties.length - 3 }}
                                </a-tag>
                            </a-space>
                        </template>
                        <template v-else-if="column.key === 'mqtt'">
                            <div class="mono">{{ record.mqttNetworkId || '--' }}</div>
                            <div class="muted">{{ record.mqttTopicPrefix || 'IOT/Business' }}，QoS {{ record.mqttQos || 0 }}</div>
                        </template>
                        <template v-else-if="column.key === 'expiresAt'">
                            <div>{{ formatTime(record.expiresAt) }}</div>
                            <a-tag :color="remainingSeconds(record) > 30 ? 'green' : 'orange'">
                                {{ remainingSeconds(record) }} 秒
                            </a-tag>
                        </template>
                        <template v-else-if="column.key === 'forward'">
                            <a-tag :color="record.forwardCount > 0 ? 'green' : 'default'">
                                {{ record.forwardCount > 0 ? '已转发' : '暂无转发' }}
                            </a-tag>
                            <span class="muted">次数 {{ record.forwardCount || 0 }}</span>
                            <div v-if="record.lastForwardTime" class="muted">
                                {{ formatTime(record.lastForwardTime) }}
                            </div>
                        </template>
                        <template v-else-if="column.key === 'action'">
                            <a-space>
                                <a-button type="link" @click="showDetail(record)">详情</a-button>
                                <a-button type="link" @click="handleRenew(record)">续期</a-button>
                                <a-popconfirm
                                    title="确认关闭此 lease？"
                                    ok-text="确认"
                                    cancel-text="取消"
                                    @confirm="handleClose(record)"
                                >
                                    <a-button type="link" danger>关闭</a-button>
                                </a-popconfirm>
                            </a-space>
                        </template>
                    </template>
                </a-table>
            </div>
        </FullPage>

        <a-modal
            v-model:open="createVisible"
            title="新增设备级临时订阅"
            :confirm-loading="saving"
            width="720px"
            ok-text="创建"
            cancel-text="取消"
            :maskClosable="false"
            @ok="handleCreate"
        >
            <a-form ref="formRef" layout="vertical" :model="formState" :rules="rules">
                <a-row :gutter="16">
                    <a-col :span="12">
                        <a-form-item label="产品 ID" name="productId">
                            <a-input v-model:value="formState.productId" allow-clear placeholder="用于订阅设备 EventBus topic" />
                        </a-form-item>
                    </a-col>
                    <a-col :span="12">
                        <a-form-item label="MQTT 客户端网络 ID" name="mqttNetworkId">
                            <a-input v-model:value="formState.mqttNetworkId" allow-clear placeholder="NetworkManager 中的 MQTT_CLIENT ID" />
                        </a-form-item>
                    </a-col>
                </a-row>
                <a-row :gutter="16">
                    <a-col :span="16">
                        <a-form-item label="Topic 前缀" name="mqttTopicPrefix">
                            <a-input v-model:value="formState.mqttTopicPrefix" allow-clear placeholder="默认 IOT/Business" />
                        </a-form-item>
                    </a-col>
                    <a-col :span="8">
                        <a-form-item label="QoS" name="mqttQos">
                            <a-select v-model:value="formState.mqttQos">
                                <a-select-option :value="0">0</a-select-option>
                                <a-select-option :value="1">1</a-select-option>
                                <a-select-option :value="2">2</a-select-option>
                            </a-select>
                        </a-form-item>
                    </a-col>
                </a-row>
                <a-form-item label="设备 ID" name="deviceIdsText">
                    <a-textarea
                        v-model:value="formState.deviceIdsText"
                        :rows="6"
                        placeholder="多个设备 ID 用逗号、换行或空格分隔"
                    />
                </a-form-item>
                <a-alert
                    show-icon
                    type="info"
                    message="新增时默认订阅设备全部采集项；关闭页面 lease 不会影响其他用户的 lease。"
                />
            </a-form>
        </a-modal>

        <a-modal
            v-model:open="detailVisible"
            title="Lease 详情"
            width="920px"
            :footer="null"
        >
            <a-descriptions v-if="currentLease" bordered size="small" :column="2">
                <a-descriptions-item label="Lease ID" :span="2">
                    <span class="mono">{{ currentLease.leaseId }}</span>
                </a-descriptions-item>
                <a-descriptions-item label="产品 ID">{{ currentLease.productId }}</a-descriptions-item>
                <a-descriptions-item label="MQTT 网络">{{ currentLease.mqttNetworkId }}</a-descriptions-item>
                <a-descriptions-item label="Topic 前缀">{{ currentLease.mqttTopicPrefix || 'IOT/Business' }}</a-descriptions-item>
                <a-descriptions-item label="QoS">{{ currentLease.mqttQos || 0 }}</a-descriptions-item>
                <a-descriptions-item label="创建时间">{{ formatTime(currentLease.createdAt) }}</a-descriptions-item>
                <a-descriptions-item label="过期时间">{{ formatTime(currentLease.expiresAt) }}</a-descriptions-item>
                <a-descriptions-item label="转发次数">{{ currentLease.forwardCount || 0 }}</a-descriptions-item>
                <a-descriptions-item label="最近转发">{{ formatTime(currentLease.lastForwardTime) }}</a-descriptions-item>
                <a-descriptions-item label="最近转发设备">{{ currentLease.lastForwardDeviceId || '--' }}</a-descriptions-item>
                <a-descriptions-item label="最近转发采集项">
                    {{ (currentLease.lastForwardProperties || []).join(', ') || '--' }}
                </a-descriptions-item>
            </a-descriptions>

            <a-table
                class="detail-table"
                size="small"
                row-key="deviceId"
                :pagination="false"
                :columns="deviceColumns"
                :data-source="currentDeviceRows"
            >
                <template #bodyCell="{ column, record }">
                    <template v-if="column.key === 'deviceId'">
                        <span class="mono">{{ record.deviceId }}</span>
                    </template>
                    <template v-else-if="column.key === 'eventBus'">
                        <a-tag :color="record.active ? 'green' : 'red'">
                            {{ record.active ? '已订阅' : '未订阅' }}
                        </a-tag>
                    </template>
                    <template v-else-if="column.key === 'properties'">
                        <a-tag v-if="!currentLease?.watchedProperties?.length" color="blue">全部采集项</a-tag>
                        <span v-else>{{ currentLease.watchedProperties.join(', ') }}</span>
                    </template>
                </template>
            </a-table>
        </a-modal>
    </j-page-container>
</template>

<script lang="ts" setup name="MqttForwardLeasePage">
import dayjs from 'dayjs';
import { onlyMessage } from '@/utils/comm';
import {
    closeLease,
    createLeaseByDevices,
    queryActiveLeases,
    renewLease,
    type MqttForwardActive,
    type MqttForwardLease,
} from '../../../api/mqttForwardSubscription';

const activeInfo = ref<Partial<MqttForwardActive>>({});
const leases = ref<MqttForwardLease[]>([]);
const loading = ref(false);
const saving = ref(false);
const createVisible = ref(false);
const detailVisible = ref(false);
const currentLease = ref<MqttForwardLease>();
const formRef = ref();
const now = ref(Date.now());
let refreshTimer: number | undefined;
let clockTimer: number | undefined;

const formState = reactive({
    productId: '',
    mqttNetworkId: '',
    mqttTopicPrefix: 'IOT/Business',
    mqttQos: 0,
    deviceIdsText: '',
});

const rules = {
    productId: [{ required: true, message: '请输入产品 ID' }],
    mqttNetworkId: [{ required: true, message: '请输入 MQTT 客户端网络 ID' }],
    deviceIdsText: [{ required: true, message: '请输入至少一个设备 ID' }],
};

const columns = [
    { title: 'Lease ID', key: 'leaseId', dataIndex: 'leaseId', width: 280, fixed: 'left' },
    { title: '设备订阅', key: 'devices', dataIndex: 'deviceIds', width: 220 },
    { title: '采集项', key: 'properties', dataIndex: 'watchedProperties', width: 160 },
    { title: 'MQTT 目标', key: 'mqtt', width: 240 },
    { title: '过期时间', key: 'expiresAt', dataIndex: 'expiresAt', width: 190 },
    { title: '转发概况', key: 'forward', width: 190 },
    { title: '操作', key: 'action', fixed: 'right', width: 180 },
];

const deviceColumns = [
    { title: '设备 ID', key: 'deviceId', dataIndex: 'deviceId' },
    { title: 'EventBus 订阅', key: 'eventBus', dataIndex: 'active', width: 140 },
    { title: '采集项', key: 'properties', width: 180 },
];

const forwardedLeaseCount = computed(() => {
    return activeInfo.value.forwardedLeaseCount ?? leases.value.filter((lease) => lease.forwardCount > 0).length;
});

const totalForwardCount = computed(() => {
    return activeInfo.value.totalForwardCount ?? leases.value.reduce((total, lease) => total + (lease.forwardCount || 0), 0);
});

const lastForwardTime = computed(() => {
    return activeInfo.value.lastForwardTime || Math.max(0, ...leases.value.map((lease) => lease.lastForwardTime || 0));
});

const currentDeviceRows = computed(() => {
    if (!currentLease.value) {
        return [];
    }
    const deviceSubscriptions = activeInfo.value.deviceSubscriptions || {};
    return currentLease.value.deviceIds.map((deviceId) => ({
        deviceId,
        active: deviceSubscriptions[deviceId] === true,
    }));
});

const previewItems = (items: string[] = []) => items.slice(0, 3);

const formatTime = (time?: number) => {
    return time ? dayjs(time).format('YYYY-MM-DD HH:mm:ss') : '--';
};

const remainingSeconds = (lease: MqttForwardLease) => {
    return Math.max(0, Math.ceil(((lease.expiresAt || 0) - now.value) / 1000));
};

const parseDeviceIds = (value: string) => {
    return Array.from(new Set(
        value
            .split(/[\s,，]+/)
            .map((item) => item.trim())
            .filter(Boolean),
    ));
};

const loadActive = async () => {
    loading.value = true;
    try {
        const resp = await queryActiveLeases();
        if (resp.status === 200) {
            activeInfo.value = resp.result || {};
            leases.value = resp.result?.leases || [];
        } else {
            onlyMessage('查询 MQTT 转发 lease 失败', 'error');
        }
    } finally {
        loading.value = false;
    }
};

const openCreate = () => {
    createVisible.value = true;
    Object.assign(formState, {
        productId: '',
        mqttNetworkId: '',
        mqttTopicPrefix: 'IOT/Business',
        mqttQos: 0,
        deviceIdsText: '',
    });
    nextTick(() => formRef.value?.clearValidate?.());
};

const handleCreate = async () => {
    await formRef.value?.validate();
    const deviceIds = parseDeviceIds(formState.deviceIdsText);
    if (!deviceIds.length) {
        onlyMessage('请输入至少一个设备 ID', 'error');
        return;
    }
    saving.value = true;
    try {
        const resp = await createLeaseByDevices({
            productId: formState.productId.trim(),
            mqttNetworkId: formState.mqttNetworkId.trim(),
            mqttTopicPrefix: formState.mqttTopicPrefix?.trim(),
            mqttQos: formState.mqttQos,
            deviceIds,
            watchedProperties: '',
        });
        if (resp.status === 200) {
            onlyMessage('创建成功');
            createVisible.value = false;
            await loadActive();
        } else {
            onlyMessage('创建失败', 'error');
        }
    } finally {
        saving.value = false;
    }
};

const handleRenew = async (lease: MqttForwardLease) => {
    const resp = await renewLease(lease.leaseId);
    if (resp.status === 200) {
        onlyMessage('续期成功');
        await loadActive();
    } else {
        onlyMessage('续期失败', 'error');
    }
};

const handleClose = async (lease: MqttForwardLease) => {
    const resp = await closeLease(lease.leaseId);
    if (resp.status === 200) {
        onlyMessage('已关闭');
        await loadActive();
    } else {
        onlyMessage('关闭失败', 'error');
    }
};

const showDetail = (lease: MqttForwardLease) => {
    currentLease.value = lease;
    detailVisible.value = true;
};

onMounted(() => {
    loadActive();
    refreshTimer = window.setInterval(loadActive, 10000);
    clockTimer = window.setInterval(() => {
        now.value = Date.now();
    }, 1000);
});

onUnmounted(() => {
    if (refreshTimer) {
        window.clearInterval(refreshTimer);
    }
    if (clockTimer) {
        window.clearInterval(clockTimer);
    }
});
</script>

<style lang="less" scoped>
.mqtt-forward-lease-page {
    .toolbar {
        display: flex;
        align-items: center;
        justify-content: space-between;
        margin-bottom: 16px;
    }

    .toolbar-tip,
    .muted {
        color: rgba(0, 0, 0, 0.45);
        font-size: 12px;
    }

    .summary-grid {
        display: grid;
        grid-template-columns: repeat(6, minmax(140px, 1fr));
        gap: 12px;
        margin-bottom: 16px;
    }

    .summary-item {
        min-height: 76px;
        padding: 12px 16px;
        background: #fff;
        border: 1px solid #f0f0f0;
        border-radius: 6px;
    }

    .summary-label {
        margin-bottom: 8px;
        color: rgba(0, 0, 0, 0.45);
        font-size: 12px;
    }

    .summary-value {
        color: rgba(0, 0, 0, 0.88);
        font-weight: 600;
        font-size: 24px;
        line-height: 30px;

        &.small {
            font-size: 14px;
            line-height: 22px;
        }
    }
}

.mono {
    font-family: Consolas, Monaco, 'Courier New', monospace;
}

.strong {
    color: rgba(0, 0, 0, 0.88);
    font-weight: 500;
}

.detail-table {
    margin-top: 16px;
}

@media (max-width: 1280px) {
    .mqtt-forward-lease-page {
        .summary-grid {
            grid-template-columns: repeat(3, minmax(160px, 1fr));
        }
    }
}
</style>
