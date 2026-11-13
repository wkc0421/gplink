import { createApp, h } from 'vue'
import Antd, { ConfigProvider } from 'ant-design-vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import 'ant-design-vue/dist/reset.css'
import Page from '../../src/modules/device-manager-ui/views/device/HistoryStatistics/index.vue'
createApp({ render: () => h(ConfigProvider, { locale: zhCN }, { default: () => h(Page) }) })
  .use(Antd).component('j-page-container', { setup(_, { slots }) { return () => h('main', slots.default?.()) } }).mount('#app')
document.body.style.cssText = 'background:#f5f7fa;color:#222;padding:16px;--app-surface:#fff;--app-text-secondary:#64748b;'
