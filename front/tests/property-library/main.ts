import { createApp, defineComponent, h } from 'vue'
import Antd, { Button, Popconfirm, ConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import 'ant-design-vue/dist/reset.css'
import Page from '../../src/modules/device-manager-ui/views/device/PropertyLibrary/index.vue'

const readonly = new URLSearchParams(location.search).has('readonly')
const PermissionButton = defineComponent({
  inheritAttrs: false,
  props: ['hasPermission', 'popConfirm'],
  setup(props, { attrs, slots }) {
    return () => {
      if (readonly && !String(props.hasPermission).endsWith(':view')) return null
      const button = () => h(Button, attrs, slots)
      return props.popConfirm ? h(Popconfirm, props.popConfirm, { default: button }) : button()
    }
  },
})
createApp({ render: () => h(ConfigProvider, { locale: zhCN }, { default: () => h(Page) }) }).use(Antd).component('j-page-container', { setup(_, { slots }) { return () => h('main', { style: 'max-width:1500px;margin:24px auto;' }, slots.default?.()) } }).component('j-permission-button', PermissionButton).mount('#app')
document.body.style.cssText = 'background:#f4f6f9;color:#1f2937;--app-surface:#fff;--app-border:#e8e8e8;--app-text:#1f2937;--app-text-secondary:#64748b;padding:0 16px;'
