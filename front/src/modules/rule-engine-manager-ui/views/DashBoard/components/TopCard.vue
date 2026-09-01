<template>
    <div class="top-card">
        <div class="top-card-content">
            <div class="content-left">
                <div class="content-left-title">
                    <span>{{ title }}</span>
                    <a-tooltip placement="top" v-if="tooltip">
                        <template #title>
                            <span>{{ tooltip }}</span>
                        </template>
                        <AIcon type="QuestionCircleOutlined" />
                    </a-tooltip>
                </div>
                <div class="content-left-value">{{ value }}</div>
            </div>
            <div class="content-right" v-if="img">
                <img :src="img" alt="" />
            </div>
            <div class="content-right-echart" v-else>
                <slot></slot>
            </div>
        </div>
        <div class="top-card-footer">
            <template v-for="(item, index) in footer" :key="index">
                <span v-if="!item.status">{{ item.title }}</span>
                <a-badge v-else :text="item.title" :status="item.status" />
                <div class="footer-item-value">{{ item.value }}</div>
            </template>
        </div>
    </div>
</template>

<script setup lang="ts">
const props = defineProps({
    title: { type: String, default: '' },
    tooltip: { type: String, default: '' },
    img: { type: String, default: '' },
    footer: { type: Array , default: '' },
    value: { type: Number, default: 0 },
});
</script>

<style lang="less" scoped>
.top-card {
    display: flex;
    flex-direction: column;
    // height: 200px;
    padding: 24px;
    background-color: @app-surface;
    border: 1px solid @app-border;
    border-radius: @app-radius-card;
    .top-card-content {
        display: flex;
        flex-direction: row;
        flex-grow: 1;
        justify-content: space-between;
        .content-left {
            height: 100%;
            width: 50%;
            &-title {
                color: @app-text-secondary;
                font-size: 13px;
                font-weight: 500;
                line-height: 20px;
            }
            &-value {
                padding: 12px 0;
                color: @app-text;
                font-weight: 600;
                font-size: 32px;
                line-height: 38px;
            }
        }
        .content-right {
            width: 0;
            height: 123px;
            display: flex;
            flex-grow: .7;
            align-items: flex-end;
            justify-content: flex-end;
            img {
                width: 100px;
                height: 100px;
            }
        }
        .content-right-echart{
            height: 123px;
            display: flex;
            flex-grow: 1;
            align-items: flex-end;
            justify-content: flex-end;
        }
    }
    .top-card-footer {
        display: flex;
        align-items: center;
        justify-content: space-between;
        padding-top: 16px;
        border-top: 1px solid @app-border;
        .footer-item-value {
            color: @app-text;
            font-weight: 600;
            font-size: 16px;
            line-height: 24px;
        }
    }
}
</style>
