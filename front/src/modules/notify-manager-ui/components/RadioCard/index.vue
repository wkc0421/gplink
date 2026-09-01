<!-- 单选卡片 -->
<template>
    <div
        :class="[
            layout === 'horizontal' ? 'm-radio-checked' : 'm-radio',
            disabled ? 'disabled' : '',
        ]"
    >
        <div
            :class="[
                layout === 'horizontal'
                    ? 'm-radio-checked-item'
                    : 'm-radio-item',
                checkStyle && myValue === item.value ? 'checked' : '',
                { active: myValue === item.value },
                item.disabled ? 'disabled' : '',
            ]"
            v-for="(item, index) in options"
            :key="index"
            @click="handleRadio(item)"
        >
            <img v-if="item.logo" class="img" :src="item.logo" alt="" />
            <span>
              <j-ellipsis>
                {{ item.label }}
              </j-ellipsis>
            </span>
            <div
                :class="[
                    'checked-icon',
                    (disabled && myValue === item.value) || item.disabled
                        ? 'checked-icon-disabled'
                        : '',
                ]"
            >
                <div><CheckOutlined /></div>
            </div>
        </div>
    </div>
</template>

<script setup lang="ts">
import { PropType } from 'vue';
import { CheckOutlined } from '@ant-design/icons-vue';

interface IOption {
    label: string;
    value: string;
    logo: string;
    disabled?: boolean;
}

type Emits = {
    (e: 'update:modelValue', data: string): void;
    (e: 'change'): void;
};
const emit = defineEmits<Emits>();

const props = defineProps({
    options: {
        type: Array as PropType<IOption[]>,
        default: () => [],
    },
    modelValue: {
        type: String,
        default: '',
    },
    layout: {
        type: String,
        default: 'vertical', //'horizontal'|'vertical' 水平|垂直
    },
    checkStyle: {
        type: Boolean,
        default: false, //是否有小勾样式
    },
    disabled: {
        type: Boolean,
        default: false,
    },
});

const myValue = computed({
    get: () => props.modelValue,
    set: (val) => {
        if (!props.disabled) {
            emit('update:modelValue', val);
            emit('change');
        }
    },
});

const handleRadio = (item: any) => {
    if (item.disabled) return;
    myValue.value = item.value;
};
</script>

<style lang="less" scoped>
.m-radio-checked {
    display: flex;
    flex-wrap: wrap;
    justify-content: space-between;
    gap: 24px;
    .disabled {
        >div {
          color: var(--app-text-secondary);
          border-color: var(--app-border);
          cursor: not-allowed;
        }

    }
    &-item {
        flex:1;
        height: 70px;
        padding: 10px 15px;
        margin-bottom: 12px;
        border: 1px solid var(--app-border);
        border-radius: 2px;
        display: flex;
        align-items: center;

        cursor: pointer;
        .img {
            width: 32px;
            height: 32px;
        }
        &.active {
            color: @primary-color;
            border-color: @primary-color;
        }
        &.disabled {
            cursor: not-allowed !important;
        }
    }
}
.checked-icon {
    position: absolute;
    right: -1px;
    bottom: -1px;
    z-index: 2;
    display: none;
    width: 18px;
    height: 18px;
    color: #fff;
    border: var(--app-primary) 18px solid;
    border-left-color: transparent;
    border-top-color: transparent;

    > div {
        font-size: 12px;
    }
}
.checked {
    position: relative;
    color: var(--app-primary);
    border-color: var(--app-primary);

    .checked-icon {
        display: block;
    }
}

//.disabled {
//    color: rgba(0, 0, 0, 0.25) !important;
//    cursor: not-allowed;
//}
//.active-checked-disabled {
//    color: rgba(0, 0, 0, 0.25) !important;
//    border: 1px #d9d9d9 solid !important;
//}
.checked-icon-disabled {
    color: var(--app-text-secondary) !important;
    border-color: var(--app-border) !important;
    border-left-color: transparent !important;
    border-top-color: transparent !important;
    cursor: not-allowed;
}

.m-radio {
    display: flex;
    gap: 16px;

    &.disabled {
      >div {
        opacity: 0.7;
        cursor: not-allowed !important;
      }
    }

    &-item {
        width: 140px;
        height: 140px;
        padding: 10px 16px;
        border: 1px solid var(--app-border);
        border-radius: 2px;
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 6px;
        cursor: pointer;
        .img {
            width: 100px;
            height: 100px;
        }
        &.active {
            color: @primary-color;
            border-color: @primary-color;
        }

        &.disabled {
            cursor: not-allowed !important;
        }

    }

}
</style>
