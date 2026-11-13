import { createApp, h } from 'vue'
import Antd from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import Page from '../../src/modules/device-manager-ui/views/device/DataQueryTest/index.vue'
createApp(Page).use(Antd).component('j-page-container', { setup(_, { slots }) { return () => h('main', { style: 'max-width:1200px;margin:24px auto' }, slots.default?.()) } }).mount('#app')
