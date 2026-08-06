<template>
    <j-page-container>
        <full-page>
            <div class="application-log-page">
                <div class="toolbar">
                    <a-space :size="12" wrap>
                        <span class="field-label">行数</span>
                        <a-input-number
                            v-model:value="lineCount"
                            :min="1"
                            :max="MAX_LINES"
                            :precision="0"
                            class="line-input"
                            @pressEnter="fetchLog"
                        />
                        <a-button type="primary" :loading="loading" @click="fetchLog">
                            <template #icon><AIcon type="ReloadOutlined" /></template>
                            刷新
                        </a-button>
                        <a-button :disabled="!logContent" @click="copyLog">
                            <template #icon><AIcon type="CopyOutlined" /></template>
                            复制
                        </a-button>
                    </a-space>
                    <a-tag v-if="logData.truncated" color="orange">已截断</a-tag>
                </div>

                <a-alert
                    v-if="errorMessage"
                    class="load-error"
                    type="error"
                    show-icon
                    :message="errorMessage"
                />

                <a-descriptions class="meta" size="small" bordered :column="4">
                    <a-descriptions-item label="日志文件" :span="2">
                        <j-ellipsis>{{ logData.path || '--' }}</j-ellipsis>
                    </a-descriptions-item>
                    <a-descriptions-item label="文件大小">
                        {{ fileSizeText }}
                    </a-descriptions-item>
                    <a-descriptions-item label="更新时间">
                        {{ lastModifiedText }}
                    </a-descriptions-item>
                    <a-descriptions-item label="返回行数">
                        {{ logData.lineCount || 0 }} / {{ logData.maxLines || normalizedLineCount }}
                    </a-descriptions-item>
                </a-descriptions>

                <a-spin :spinning="loading" class="log-spin">
                    <div class="log-panel">
                        <a-empty v-if="!logContent" class="empty" />
                        <pre v-else class="log-content">{{ logContent }}</pre>
                    </div>
                </a-spin>
            </div>
        </full-page>
    </j-page-container>
</template>

<script setup lang="ts" name="ApplicationLogPage">
import dayjs from 'dayjs';
import { latestApplicationLog } from '../../../api/link/log';
import { onlyMessage } from '@/utils/comm';

type ApplicationLogData = {
    path?: string;
    size?: number;
    lastModified?: number;
    maxLines?: number;
    lineCount?: number;
    truncated?: boolean;
    content?: string;
};

const MAX_LINES = 10000;
const DEFAULT_LINES = 500;
const REFRESH_INTERVAL = 5000;

const loading = ref(false);
const lineCount = ref(DEFAULT_LINES);
const logData = ref<ApplicationLogData>({});
const errorMessage = ref('');
let refreshTimer: ReturnType<typeof setInterval> | undefined;

const normalizedLineCount = computed(() => {
    const value = Math.floor(Number(lineCount.value) || DEFAULT_LINES);
    return Math.min(Math.max(value, 1), MAX_LINES);
});

const logContent = computed(() => logData.value.content || '');

const fileSizeText = computed(() => formatFileSize(logData.value.size));

const lastModifiedText = computed(() => {
    return logData.value.lastModified
        ? dayjs(logData.value.lastModified).format('YYYY-MM-DD HH:mm:ss')
        : '--';
});

const normalizeResponse = (response: any): ApplicationLogData => {
    return response?.result || response || {};
};

const fetchLog = async () => {
    if (loading.value) {
        return;
    }
    const lines = normalizedLineCount.value;
    lineCount.value = lines;
    loading.value = true;
    errorMessage.value = '';
    try {
        const response = await latestApplicationLog({ lines });
        logData.value = normalizeResponse(response);
    } catch (error: any) {
        logData.value = {};
        errorMessage.value = error?.message || '日志加载失败';
        onlyMessage(errorMessage.value, 'error');
    } finally {
        loading.value = false;
    }
};

const copyLog = async () => {
    const text = logContent.value;
    if (!text) {
        return;
    }
    try {
        if (navigator.clipboard) {
            await navigator.clipboard.writeText(text);
        } else {
            const input = document.createElement('textarea');
            input.value = text;
            document.body.appendChild(input);
            input.select();
            document.execCommand('copy');
            document.body.removeChild(input);
        }
        onlyMessage('复制成功');
    } catch (error: any) {
        onlyMessage(error?.message || '复制失败', 'error');
    }
};

const formatFileSize = (size?: number) => {
    if (!size && size !== 0) {
        return '--';
    }
    if (size < 1024) {
        return `${size} B`;
    }
    const units = ['KB', 'MB', 'GB'];
    let value = size / 1024;
    let index = 0;
    while (value >= 1024 && index < units.length - 1) {
        value /= 1024;
        index++;
    }
    return `${value.toFixed(2)} ${units[index]}`;
};

onMounted(() => {
    fetchLog();
    refreshTimer = setInterval(fetchLog, REFRESH_INTERVAL);
});

onUnmounted(() => {
    if (refreshTimer) {
        clearInterval(refreshTimer);
    }
});
</script>

<style scoped lang="less">
.application-log-page {
    height: 100%;
    min-height: 0;
    display: flex;
    flex-direction: column;
}

.toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    margin-bottom: 12px;
}

.field-label {
    color: rgba(0, 0, 0, 0.85);
}

.line-input {
    width: 160px;
}

.meta {
    margin-bottom: 12px;
}

.load-error {
    margin-bottom: 12px;
}

.log-spin {
    min-height: 0;
    flex: 1;
}

:deep(.log-spin .ant-spin-container) {
    height: 100%;
    min-height: 0;
}

.log-panel {
    height: 100%;
    min-height: 360px;
    overflow: auto;
    border: 1px solid #d9d9d9;
    background: #0f1720;
}

.log-content {
    min-height: 100%;
    margin: 0;
    padding: 12px;
    color: #d5e0ea;
    font-size: 12px;
    line-height: 20px;
    font-family: Consolas, Monaco, 'Courier New', monospace;
    white-space: pre-wrap;
    word-break: break-word;
}

.empty {
    padding-top: 120px;
    background: #fff;
    min-height: 360px;
}
</style>
