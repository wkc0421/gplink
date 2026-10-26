// Real network list/detail components with local HTTP APIs for browser regression.
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import path from 'node:path'

const root = path.resolve(import.meta.dirname, '../..')
const virtual = {
  '@jetlinks-web/core': `
    const call = async (method, url, data) => (await fetch('/mock' + url, {
      method, headers: {'Content-Type':'application/json'},
      body: method === 'GET' || data === undefined ? undefined : JSON.stringify(data),
    })).json();
    export const request = Object.fromEntries(['get','post','patch','remove'].map(method => [method,
      (url, data) => call(method === 'remove' ? 'DELETE' : method.toUpperCase(), url, data)]));
  `,
  '@jetlinks-web/utils': `export const onlyMessage = message => (window.__messages ||= []).push(message);
    export const randomString = () => Math.random().toString(36).slice(2);`,
  '@/utils/utils': 'export const isNoCommunity = true;',
  '@/utils/consts': 'export const langKey = "Accept-Language";',
  '@/utils/comm': 'export const onlyMessage = message => (window.__messages ||= []).push(message);',
  '@/hooks': 'export const useTabSaveSuccessBack = () => ({onBack: () => {}});',
  '@/store': `import {useRouter} from 'vue-router';
    export const useMenuStore = () => { const router = useRouter(); return {
      jumpPage: (name, options) => router.push({name, ...options}),
    }};`,
  'network-fixture-assets': 'export const network = {icon: ""};',
  '/__network-entry': `
    import {createApp, defineComponent, h, ref, onMounted} from 'vue';
    import {createRouter, createWebHistory, RouterView} from 'vue-router';
    import {createPinia} from 'pinia';
    import Antd, {Button} from 'ant-design-vue';
    import 'ant-design-vue/dist/reset.css';
    import i18n from '/src/locales/index.ts';
    import List from '/src/modules/device-manager-ui/views/link/Type/index.vue';
    import Detail from '/src/modules/device-manager-ui/views/link/Type/Detail/index.vue';
    const router = createRouter({history:createWebHistory(), routes:[
      {path:'/', component:List},
      {name:'link/Type/Detail', path:'/network/:id', component:Detail},
    ]});
    const app = createApp({render: () => h(RouterView)});
    app.use(Antd).use(createPinia()).use(i18n).use(router);
    const Wrapper = defineComponent({setup: (_, {slots}) => () => h('div', slots.default?.())});
    for (const name of ['j-page-container','FullPage','j-ellipsis']) app.component(name, Wrapper);
    app.component('j-permission-button', defineComponent({inheritAttrs:false,
      setup:(_, {attrs,slots}) => () => h(Button, attrs, slots)}));
    app.component('AIcon', defineComponent({setup: () => () => h('span')}));
    app.component('pro-search', defineComponent({setup: () => () => null}));
    app.component('CardBox', defineComponent({props:['actions'], setup:(props,{slots}) => () =>
      h('article', {class:'network-card'}, [slots.content?.(), ...(props.actions || []).map(action =>
        h(Button, {disabled:action.disabled, onClick:event => {event.stopPropagation(); action.onClick?.()}}, () => action.text))])}));
    app.component('j-pro-table', defineComponent({props:['request'], setup:(props,{slots}) => {
      const rows = ref([]);
      onMounted(async () => { const response = await props.request({}); rows.value = response.result.data });
      return () => h('div', [slots.headerLeftRender?.(), ...rows.value.map(row => h('section', [
        slots.card?.(row), h('div', {'data-testid':'table-type'}, slots.type?.(row)),
        h('div', {'data-testid':'table-details'}, slots.details?.(row)),
      ]))]);
    }}));
    await router.isReady();
    app.mount('#app');
  `,
}
const server = await createServer({
  configFile:false, root,
  resolve:{alias:[
    ...Object.keys(virtual).filter(key => key.startsWith('@/')).map(key => ({find:key, replacement:'\0' + key})),
    {find:'@', replacement:path.join(root,'src')},
  ]},
  optimizeDeps:{entries:[], include:['vue','vue-router','pinia','vue-i18n','ant-design-vue','lodash-es']},
  server:{host:'127.0.0.1', port:9385, strictPort:true},
  css:{preprocessorOptions:{less:{modifyVars:{'error-color':'#ff4d4f'}, javascriptEnabled:true}}},
  plugins:[{
    name:'network-component-fixture', enforce:'pre',
    resolveId(id, importer) {
      if (id.startsWith('\0') && id.slice(1) in virtual) return id
      if (id in virtual) return '\0' + id
      if (id === '../../../assets' && importer?.endsWith('/views/link/Type/index.vue')) {
        return '\0network-fixture-assets'
      }
    },
    load:id => id.startsWith('\0') ? virtual[id.slice(1)] : undefined,
    configureServer(server) {
      server.middlewares.use((req,res,next) => {
        if (!req.url?.startsWith('/network/') && req.url !== '/') return next();
        res.setHeader('Content-Type','text/html; charset=utf-8');
        res.end('<!doctype html><html><body><div id="app"></div><script type="module" src="/__network-entry"></script></body></html>');
      });
    },
  }, vue(), AutoImport({imports:['vue','vue-router'], dts:false})],
})
await server.listen()
console.log('Network component fixture ready on 127.0.0.1:9385')
for (const signal of ['SIGINT','SIGTERM']) process.on(signal, async () => {await server.close(); process.exit(0)})
