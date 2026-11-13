import { createApp } from 'vue'
import Antd, { Button } from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import i18n, { AIcon } from './mock'
import Harness from './Harness.vue'
import { createPinia } from 'pinia'
import { createRouter, createWebHistory, RouterView } from 'vue-router'
import { h } from 'vue'
import RealBase from './RealBase.vue'
const real = new URLSearchParams(location.search).has('real')
const router = createRouter({ history: createWebHistory(), routes: [{ path: '/', component: real ? RealBase : Harness }] })
createApp(RouterView).use(createPinia()).use(router).use(Antd).use(i18n)
  .component('AIcon', AIcon).component('j-empty', { template: '<div>暂无数据</div>' })
  .component('j-permission-button', { inheritAttrs: false, setup(_, { attrs, slots }) { return () => h(Button, { ...attrs, onClick: attrs.onClick, disabled: attrs.disabled }, slots) } })
  .component('j-ellipsis', { template: '<span><slot /></span>' }).mount('#app')
