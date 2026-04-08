import { computed, defineComponent, h, useAttrs } from 'vue';
import { Button, Modal, Tooltip } from 'ant-design-vue';
import { useAuthStore } from '@/store';

type PopConfirmConfig = {
    title?: string;
    content?: string;
    onConfirm?: () => unknown;
    onCancel?: () => unknown;
};

export default defineComponent({
    name: 'JPermissionButton',
    inheritAttrs: false,
    props: {
        hasPermission: {
            type: [String, Array, Boolean],
            default: undefined,
        },
        tooltip: {
            type: Object,
            default: undefined,
        },
        popConfirm: {
            type: Object,
            default: undefined,
        },
        noPermissionTitle: {
            type: String,
            default: '暂无权限，请联系管理员',
        },
        popConfirmBefore: {
            type: Function,
            default: undefined,
        },
    },
    setup(props, { slots }) {
        const attrs = useAttrs();
        const authStore = useAuthStore();

        const permission = computed(() => {
            if (props.hasPermission === true || props.hasPermission === undefined) {
                return true;
            }
            return authStore.hasPermission(props.hasPermission as string | string[]);
        });

        const buttonDisabled = computed(() => {
            const disabled = attrs.disabled;
            return permission.value ? !!disabled : true;
        });

        const handleClick = async (...args: unknown[]) => {
            if (!permission.value) {
                return;
            }

            const clickHandler = attrs.onClick as ((...eventArgs: unknown[]) => unknown) | undefined;

            if (props.popConfirm) {
                const nextConfirm =
                    ((await props.popConfirmBefore?.()) as PopConfirmConfig | undefined) ||
                    (props.popConfirm as PopConfirmConfig);

                Modal.confirm({
                    title: nextConfirm.title,
                    content: nextConfirm.content,
                    onOk: () => nextConfirm.onConfirm?.(),
                    onCancel: () => nextConfirm.onCancel?.(),
                });
                return;
            }

            return clickHandler?.(...args);
        };

        return () => {
            const button = h(
                Button,
                {
                    ...attrs,
                    disabled: buttonDisabled.value,
                    onClick: handleClick,
                },
                {
                    default: () => slots.default?.(),
                    icon: () => slots.icon?.(),
                },
            );

            if (!permission.value) {
                return h(
                    Tooltip,
                    {
                        title: props.noPermissionTitle,
                    },
                    {
                        default: () => button,
                    },
                );
            }

            if (props.tooltip) {
                return h(
                    Tooltip,
                    {
                        ...(props.tooltip as Record<string, unknown>),
                    },
                    {
                        default: () => button,
                    },
                );
            }

            return button;
        };
    },
});
