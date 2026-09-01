<template>
    <div class="device-count-container">
        <h5 class="title">{{ $t('components.BasicCountCard.926510-0') }}</h5>
        <span class="detail" @click="jumpPage('link/DashBoard', {})"> {{ $t('components.BasicCountCard.926510-1') }} </span>

        <div class="box-list">
            <div class="box-item">
                <div class="label">{{ $t('components.BasicCountCard.926510-2') }}</div>
                <div class="value">{{ cpu + '%' }}</div>
                <Pie
                    class="chart"
                    :value="cpu"
                    chart-ref="cpuChart"
                    :color-arr="['#ebebeb', '#d3adf7']"
                    :image="home.top3"
                />
            </div>
            <div class="box-item">
                <div class="label">{{ $t('components.BasicCountCard.926510-3') }}</div>
                <div class="value">{{ jvm + '%' }}</div>
                <Pie
                    class="chart"
                    chart-ref="jvmChart"
                    :value="jvm"
                    :color-arr="['#d6e4ff', '#85a5ff']"
                    :image="home.top3"
                />
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
import { wsClient } from '@jetlinks-web/core';
import Pie from './Pie.vue';
import { map } from 'rxjs/operators';
import { useMenuStore } from '@/store';
import { useI18n } from 'vue-i18n'
import { home } from '../../../assets'

const { t: $t } = useI18n()
const cpu = ref(0);
const jvm = ref(0);

const { jumpPage } = useMenuStore();

const cpuSocket = wsClient.getWebSocket(
    'operations-statistics-system-info-cpu-realTime',
    '/dashboard/systemMonitor/stats/info/realTime',
    {
        type: 'cpu',
        interval: '2s',
        agg: 'avg',
    },
)
    ?.pipe(map((res: any) => res.payload))
    .subscribe((resp: any) => {
        cpu.value = resp.value?.systemUsage || 0;
    });
const jvmSocket = wsClient.getWebSocket(
    `operations-statistics-system-info-memory-realTime`,
    `/dashboard/systemMonitor/stats/info/realTime`,
    {
        type: 'memory',
        interval: '2s',
        agg: 'avg',
    },
)
    ?.pipe(map((res: any) => res.payload))
    .subscribe((payload: any) => {
        jvm.value = payload.value?.jvmHeapUsage || 0;
    });

onUnmounted(() => {
    cpuSocket && cpuSocket.unsubscribe();
    jvmSocket && jvmSocket.unsubscribe();
});
</script>

<style lang="less" scoped>
.device-count-container {
    background-color: @app-surface;
    border: 1px solid @app-border;
    padding: 24px 14px;
    position: relative;
    .detail {
        color: @app-primary-hover;
        cursor: pointer;
        position: absolute;
        right: 12px;
        top: 24px;
        z-index: 3;
    }
    .title {
        position: relative;
        z-index: 2;
        display: flex;
        justify-content: space-between;
        margin-bottom: 12px;
        padding-left: 18px;
        font-weight: 700;
        font-size: 18px;

        &::before {
            position: absolute;
            top: 50%;
            left: 0;
            width: 8px;
            height: 8px;
            background-color: @app-primary;
            border: 1px solid @app-border-strong;
            transform: translateY(-50%);
            content: ' ';
        }
    }

    .box-list {
        display: grid;
        grid-template-columns: repeat(2, 1fr);
        grid-gap: 24px;
        gap: 24px;

        .box-item {
            position: relative;
            padding: 16px;
            background: linear-gradient(
                135.62deg,
                #172F49 22.27%,
                rgba(13, 25, 41, 0.86) 91.82%
            );
            border-radius: 2px;
            box-shadow: 0 4px 18px rgba(0, 0, 0, .24);

            .label {
                color: @app-text-secondary;
            }
            .value {
                margin: 20px 0;
                color: @app-text;
                font-weight: 700;
                font-size: 20px;
            }

            .chart {
                position: absolute;
                right: 10%;
                bottom: 0;
                width: 90px;
                height: 90px;
            }
        }
    }
}
</style>
