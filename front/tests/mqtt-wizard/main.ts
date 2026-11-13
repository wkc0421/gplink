import { createApp, h } from 'vue'
import Antd, { Button } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import Page from '../../src/modules/device-manager-ui/views/device/MqttClientAccess/index.vue'
import TcpPage from '../../src/modules/device-manager-ui/views/device/TcpServerAccess/index.vue'
createApp(new URLSearchParams(location.search).has('tcp') ? TcpPage : Page).use(Antd)
  .component('j-page-container', { setup(_, { slots }) { return () => h('main', slots.default?.()) } })
  .component('j-permission-button', { inheritAttrs: false, setup(_, { attrs, slots }) { return () => h(Button, attrs, slots) } })
  .mount('#app')
