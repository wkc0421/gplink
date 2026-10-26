// Isolated browser fixture: the real wizard and API adapters, with all HTTP requests intercepted by the test.
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
const virtual = {
  '@jetlinks-web/core': `
    const call = async (method, url, data) => {
      const response = await fetch('/mock' + url, {method, headers:{'Content-Type':'application/json'},
        body: method === 'GET' || data === undefined ? undefined : JSON.stringify(data)});
      return response.json();
    };
    export const request = Object.fromEntries(['get','post','put','patch','remove'].map(method => [method,
      (url, data) => call(method === 'remove' ? 'DELETE' : method.toUpperCase(), url, data)]));
    export const ndJson = () => {};
  `,
  '@jetlinks-web/utils': `export const getToken = () => ''; export const onlyMessage = (message) => { (window.__messages ||= []).push(message) };`,
  '@jetlinks-web/constants': `export const BASE_API = '/mock'; export const TOKEN_KEY_URL = 'token';`,
  '/__wizard-entry': `
    import { createApp, defineComponent, h } from 'vue';
    import Antd, { Button } from 'ant-design-vue';
    import 'ant-design-vue/dist/reset.css';
    import Wizard from '/src/modules/device-manager-ui/views/device/ModbusAccess/index.vue';
    const app = createApp(Wizard);
    app.use(Antd);
    for (const name of ['j-page-container', 'FullPage']) app.component(name, defineComponent({setup: (_, {slots}) => () => h('div', slots.default?.())}));
    app.component('j-permission-button', defineComponent({inheritAttrs:false, setup: (_, {attrs, slots}) => () => h(Button, attrs, slots)}));
    app.component('AIcon', defineComponent({setup: () => () => h('span')}));
    app.mount('#app');
  `,
}
const server = await createServer({
  configFile: false, root: path.resolve(import.meta.dirname, '../..'),
  optimizeDeps: { entries: [], include: ['vue', 'ant-design-vue'] },
  server: { host: '127.0.0.1', port: 9384, strictPort: true },
  plugins: [{ name: 'wizard-fixture', enforce: 'pre',
    resolveId: id => id in virtual ? '\0' + id : undefined,
    load: id => id.startsWith('\0') ? virtual[id.slice(1)] : undefined,
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        if (req.url !== '/') return next();
        res.setHeader('Content-Type', 'text/html; charset=utf-8');
        res.end('<!doctype html><html><body><div id="app"></div><script type="module" src="/__wizard-entry"></script></body></html>');
      });
    },
  }, vue()],
})
await server.listen()
console.log('Modbus wizard fixture ready on 127.0.0.1:9384')
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, async () => { await server.close(); process.exit(0) })
