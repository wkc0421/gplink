<template>
    <div class="device-count-container">
        <h5 class="title">{{ $t('components.DeviceCountCard.926510-0') }}</h5>
        <span class="detail" @click="jumpPage('device/DashBoard', {})"> {{ $t('components.DeviceCountCard.926510-1') }} </span>

        <div class="box-list">
            <div class="box-item">
                <div class="label">{{ $t('components.DeviceCountCard.926510-2') }}</div>
                <div class="value">{{ projectNum }}</div>
                <img :src="home.product" alt="" />
            </div>
            <div class="box-item">
                <div class="label">{{ $t('components.DeviceCountCard.926510-3') }}</div>
                <div class="value">{{ deviceNum }}</div>
                <img :src="home.top1" alt="" />
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
import { deviceCount, productCount } from '../../../api/dashboard';
import { useMenuStore } from '@/store/menu';
import { useI18n } from 'vue-i18n'
import { home } from '../../../assets'

const { t: $t } = useI18n()
const { jumpPage } = useMenuStore();
const projectNum = ref(0);
const deviceNum = ref(0);

const menuPermission = useMenuStore().hasMenu;
const getData = () => {
    // 有产品菜单权限则获取数据
    menuPermission('device/Instance') &&
    deviceCount().then((resp: any) => {
            deviceNum.value = resp.result;
        });

    // 有设备菜单权限则获取数据
    menuPermission('device/Product') &&
    productCount({}).then((resp: any) => {
            projectNum.value = resp.result;
        });
};
getData();
</script>

<style lang="less" scoped>
.device-count-container {
    background-color: @app-surface;
    border: 1px solid @app-border;
    padding: 24px 14px;
    position: relative;
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
    .detail {
        color: @app-primary-hover;
        cursor: pointer;
        position: absolute;
        right: 12px;
        top: 24px;
        z-index: 3;
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

            img {
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
