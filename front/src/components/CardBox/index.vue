<template>
  <div class="card a-table-card-box">
    <div
        class="card-warp"
        :class="{ active: active ? 'active' : '', 'disabled': disabled }"
        @click="handleClick"
    >
      <div class="card-type" v-if="slots.type">
        <div class="card-type-text">
          <slot name="type"></slot>
        </div>
      </div>
      <div
          class="card-content"
          :class="{'card-content-top-line': !slots.type}"
          :style="{ paddingTop: slots.type ? '40px' : '30px' }"
      >
        <div
            class="card-content-bg1"
            :style="{
                background: showStatus ? getBackgroundColor(statusNames[status]) : 'transparent',
            }"
        ></div>
        <div
            class="card-content-bg2"
            :style="{
                background: showStatus ? getBackgroundColor(statusNames[status]) : 'transparent',
            }"
        ></div>
        <div style="display: flex">
          <!-- 图片 -->
          <div class="card-item-avatar">
            <slot name="img">
              <img
                  :width="80"
                  :height="80"
                  v-if="imgUrl"
                  :src="imgUrl"
              />
            </slot>
          </div>
          <!-- 内容 -->
          <div class="card-item-body">
            <slot name="content">
              <j-ellipsis style="width: calc(100% - 100px);">
                <span class="card-item-heard-name">
                  {{ value.name }}
                </span>
              </j-ellipsis>
              <a-row :gutter="24">
                <a-col v-for="(_item, index) in contentList" :key="index" :span="24 / contentList.length">
                  <div class="card-item-content-text">{{ _item?.text }}</div>
                  <j-ellipsis>{{ _item?.value || "--" }}</j-ellipsis>
                </a-col>
              </a-row>
            </slot>
          </div>
        </div>
        <!-- 勾选 -->
        <div v-if="active" class="checked-icon">
          <div>
            <AIcon type="CheckOutlined"/>
          </div>
        </div>
        <!-- 状态 -->
        <div
            v-if="showStatus"
            class="card-state"
            :style="{
                        backgroundColor: getHexColor(statusNames[status]),
                    }"
        >
          <div class="card-state-content">
            <j-badge-status
                :status="status"
                :text="statusText"
                :statusNames="statusNames"
            ></j-badge-status>
          </div>
        </div>
      </div>
      <div class="card-mask" v-if="props.hasMark">
        <div class="mask-content">
          <slot name="mark"/>
        </div>
      </div>
    </div>
    <!-- 按钮 -->
    <slot name="bottom-tool">
      <div
          v-if="showTool && actions && actions.length"
          class="card-tools"
      >
        <div
            v-for="item in _actions"
            :key="item.key"
            class="card-button"
            :class="{
                delete: item.key === 'delete',
            }"
            @click.stop
        >
          <slot name="actions" v-bind="item">
            <j-permission-button
                :disabled="handleFuncValue(item.disabled, value)"
                :popConfirm="item.popConfirm ? {
                  title: handleFuncValue(item.popConfirm.title, value),
                  onConfirm: (e) => {
                    item.popConfirm.onConfirm?.(value, e)
                  }
                } : null"
                :tooltip="item.tooltip ? {
                  title: handleFuncValue(item.tooltip.title, value)
                } : null"
                @click="(e) => item.onClick?.(value, e)"
                type="link"
                style="padding: 0 5px"
                :danger="item.key === 'delete'"
                :hasPermission="item.hasPermission"
            >
              <AIcon type="DeleteOutlined" v-if="item.key === 'delete'"/>
              <template v-else>
                <AIcon :type="handleFuncValue(item.icon, value)"/>
                <span>{{ handleFuncValue(item?.text, value) }}</span>
              </template>
            </j-permission-button>
          </slot>
        </div>
      </div>
    </slot>
  </div>
</template>

<script setup lang="ts" name='CardBox'>
import {getHexColor} from '@jetlinks-web/components/es/BadgeStatus/color';
import {PropType} from 'vue';
import i18n from '@/locales';
import {handleFuncValue} from "@/components/CrudTable/utils";

type EmitProps = {
  // (e: 'update:modelValue', data: Record<string, any>): void;
  (e: 'click', data: Record<string, any>): void;
};

type TableActionsType = any;

const emit = defineEmits<EmitProps>();
const slots = useSlots();

const props = defineProps({
  value: {
    type: Object as PropType<Record<string, any>>,
    default: () => ({}),
  },
  showStatus: {
    type: Boolean,
    default: true,
  },
  showTool: {
    type: Boolean,
    default: true,
  },
  statusText: {
    type: String,
    default: () => i18n.global.t('DeviceAccess.accessModal.551011-8'),
  },
  status: {
    type: [String, Number] as PropType<string | number>,
    default: 'default',
  },
  statusNames: {
    type: Object as PropType<Record<any, any>>,
    default: () => ({'default': 'default'})
  },
  actions: {
    type: Array as PropType<TableActionsType[]>,
    default: () => [],
  },
  active: {
    type: Boolean,
    default: false,
  },
  hasMark: {
    type: Boolean,
    default: false,
  },
  disabled: {
    type: Boolean,
    default: false,
  },
  imgUrl: {
    type: String
  },
  contentList: {
    type: Array,
    default: []
  }
});

const getBackgroundColor = (code: string) => {
  const _color1 = getHexColor(code, 0.03);
  const _color2 = getHexColor(code, 0);
  return `linear-gradient(
                188.4deg,
                ${_color1} 30%,
                ${_color2} 80%
            )`;
};

const _actions = computed(() => {
  return props.actions.filter(i => handleFuncValue(i.show === undefined ? true : i.show, props.value))
})

const handleClick = () => {
  emit('click', props.value);
};
</script>

<style lang="less" scoped>
.card {
  width: 100%;
  color: @app-text;
  background-color: @app-surface;

  .checked-icon {
    position: absolute;
    right: -22px;
    bottom: -22px;
    z-index: 2;
    width: 44px;
    height: 44px;
    color: #fff;
    background-color: @primary-color;
    transform: rotate(-45deg);

    > div {
      position: relative;
      height: 100%;
      transform: rotate(45deg);

      > span {
        position: absolute;
        top: 6px;
        left: 6px;
        font-size: 12px;
      }
    }
  }

  .card-warp {
    position: relative;
    border: 1px solid fade(@app-border-strong, 62%);
    border-radius: @app-radius-card;
    background: @app-surface;
    overflow: hidden;
    cursor: pointer;
    box-shadow: 0 4px 14px rgba(0, 0, 0, .16);
    transition: border-color .2s ease, box-shadow .2s ease, transform .2s ease;

    &:hover {
      border-color: @app-primary;
      box-shadow: 0 12px 28px rgba(0, 0, 0, 0.28);
      transform: translateY(-1px);

      .card-mask {
        visibility: visible;
      }
    }

    &.disabled {
      filter: grayscale(100%);
      cursor: not-allowed;
    }

    &.active {
      position: relative;
      border: 1px solid @primary-color;
    }

    .card-type {
      position: absolute;
      top: 0;
      left: -15px;
      height: 32px;
      padding: 0 30px;
      color: @app-text-secondary;
      line-height: 32px;
      background-color: @app-elevated;
      transform: skewX(-45deg);

      .card-type-text {
        display: flex;
        align-items: center;
        justify-content: center;
        transform: skewX(45deg);
      }
    }

    .card-content {
      position: relative;
      padding: 30px 12px 30px 30px;
      overflow: hidden;
      background-color: @app-surface;

      .card-item-avatar {
        margin-right: 16px;
        display: flex;
        align-items: center;
      }

      .card-item-body {
        display: flex;
        flex-direction: column;
        flex-grow: 1;
        width: 0;
        color: @app-text-value;
        font-size: @app-font-size-base;
        line-height: @app-line-height-base;

        .ant-row {
          margin-top: 16px;

          :deep(.ant-col) {
            min-width: 0;
            color: @app-text-value;
            line-height: @app-line-height-base;
          }

          :deep(.j-ellipsis) {
            color: @app-text-value;
            font-size: @app-font-size-base;
            font-weight: @app-font-weight-medium;
            line-height: @app-line-height-base;
          }
        }
      }

      .card-state {
        position: absolute;
        top: 30px;
        right: -12px;
        display: flex;
        justify-content: center;
        padding: 0 20px 0 20px;
        background-color: rgba(47, 128, 255, 0.16);
        transform: skewX(45deg);

        &.success {
          background-color: @success-color-deprecated-bg;
        }

        &.warning {
          background-color: rgba(255, 180, 84, 0.16);
        }

        &.error {
          background-color: rgba(255, 100, 116, 0.16);
        }

        .card-state-content {
          transform: skewX(-45deg);

          :deep(.ant-badge-status-text) {
            color: @app-text-value !important;
            font-size: 13px;
            font-weight: @app-font-weight-medium;
            line-height: 20px;
          }
        }
      }

      :deep(.card-item-content-title) {
        cursor: pointer;
        font-size: @app-section-size;
        font-weight: @app-font-weight-semibold;
        line-height: @app-section-line-height;
        color: @primary-color;
        width: calc(100% - 100px);
        overflow: hidden;
        white-space: nowrap;
        text-overflow: ellipsis;
      }

      :deep(.card-item-heard-name) {
        font-weight: @app-font-weight-semibold;
        font-size: @app-section-size;
        line-height: @app-section-line-height;
        margin-bottom: 10px;
        color: @app-text;
      }

      :deep(.card-item-content-text) {
        color: @app-text-table-header;
        font-size: @app-label-size;
        font-weight: @app-font-weight-regular;
        line-height: @app-label-line-height;
        letter-spacing: .01em;
      }
    }

    .card-content-top-line {
      &::before {
        position: absolute;
        top: 0;
        left: 30px + 10px;
        display: block;
        width: 15%;
        min-width: 64px;
        height: 2px;
        background-image: url('/images/rectangle.png');
        background-repeat: no-repeat;
        background-size: 100% 100%;
        content: ' ';
      }
    }

    .card-content-bg1 {
      position: absolute;
      right: -5%;
      height: 100%;
      width: 44.65%;
      top: 0;
      background: linear-gradient(188.4deg,
      rgba(47, 128, 255, 0.08) 22.94%,
      rgba(47, 128, 255, 0) 94.62%);
      transform: skewX(-15deg);
    }

    .card-content-bg2 {
      position: absolute;
      right: -5%;
      height: 100%;
      width: calc(44.65% + 34px);
      top: 0;
      background: linear-gradient(188.4deg,
      rgba(47, 128, 255, 0.08) 22.94%,
      rgba(47, 128, 255, 0) 94.62%);
      transform: skewX(-15deg);
    }

    .card-mask {
      position: absolute;
      top: 0;
      left: 0;
      z-index: 2;
      display: flex;
      align-items: center;
      justify-content: center;
      width: 100%;
      height: 100%;
      color: #fff;
      background-color: rgba(#000, 0.5);
      visibility: hidden;
      cursor: pointer;
      transition: all 0.3s;

      .mask-content {
        display: flex;
        align-items: center;
        justify-content: center;
        width: 100%;
        height: 100%;
        padding: 0 !important;
      }
    }
  }

  &.item-active {
    position: relative;
    color: @primary-color;

    .checked-icon {
      display: block;
    }

    .card-warp {
      border: 1px solid @primary-color;
    }
  }

  .card-tools {
    display: flex;
    margin-top: 8px;

    .card-button {
      display: flex;
      flex-grow: 1;

      & > :deep(span, button) {
        width: 100%;
        border-radius: 0;
      }

      :deep(button) {
        width: 100%;
        border-radius: 6px;
        background: @app-elevated;
        border: 1px solid @app-border;
        color: @primary-color;

        &:hover {
          background-color: @primary-color-hover;
          border-color: @primary-color-hover;

          span {
            color: #fff !important;
          }
        }

        &:active {
          background-color: @primary-color-active;
          border-color: @primary-color-active;

          span {
            color: #fff !important;
          }
        }
      }

      &:not(:last-child) {
        margin-right: 8px;
      }

      &.delete {
        flex-basis: 60px;
        flex-grow: 0;

        :deep(button) {
          background: @error-color-deprecated-bg;
          border: 1px solid @error-color-outline;

          span {
            color: @error-color !important;
          }

          &:hover {
            background-color: @error-color-hover;

            span {
              color: #fff !important;
            }
          }

          &:active {
            background-color: @error-color-active;

            span {
              color: #fff !important;
            }
          }
        }
      }

      :deep(button[disabled]) {
        background: @disabled-bg;
        border-color: @disabled-color;

        span {
          color: @disabled-color !important;
        }

        &:hover {
          background-color: @disabled-active-bg;
        }

        &:active {
          background-color: @disabled-active-bg;
        }
      }

      // :deep(.ant-tooltip-disabled-compatible-wrapper) {
      //     width: 100%;
      // }
    }
  }
}
</style>
