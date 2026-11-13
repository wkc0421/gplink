import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import path from 'node:path'
export default defineConfig({
  root: __dirname,
  plugins: [
    { name: 'metadata-test-boundaries', enforce: 'pre', resolveId(source, importer) {
      if (!importer?.includes('/src/modules/device-manager-ui/')) return
      importer = importer.split('?')[0]
      if (/\/store\/(product|instance)$/.test(source)) return path.join(__dirname, 'mock.ts')
      if (/\/api\/(product|instance)$/.test(source)) return path.join(__dirname, 'baseApi.ts')
      if (importer.endsWith('/Metadata/Base/Base.vue') && ['./components', './DetailModal', './components/Import', '../../../data'].includes(source)) return path.join(__dirname, 'baseWidgets.ts')
      if (importer.endsWith('/Metadata/Base/Base.vue') && source.endsWith('/components/Metadata')) return path.join(__dirname, 'baseWidgets.ts')
      if (importer.endsWith('/Metadata/Base/PropertyEditor.vue') && (source === './components' || source.endsWith('/components/Metadata'))) return path.join(__dirname, 'baseWidgets.ts')
    } },
    vue(), AutoImport({ imports: ['vue', 'vue-router'], dts: false }),
  ],
  server: { host: '127.0.0.1', port: 5207, strictPort: true, fs: { allow: [path.resolve(__dirname, '../..')] } },
  resolve: { alias: {
    '@jetlinks-web/core': path.join(__dirname, 'mock.ts'),
    '@jetlinks-web/utils': path.join(__dirname, 'mock.ts'),
    '@jetlinks-web/components': path.join(__dirname, 'mock.ts'),
    '@/locales': path.join(__dirname, 'mock.ts'),
    '@/store': path.join(__dirname, 'mock.ts'),
    '@/utils/comm': path.join(__dirname, 'mock.ts'),
    '@/utils': path.join(__dirname, 'mock.ts'),
  } },
  css: { preprocessorOptions: { less: { modifyVars: { 'app-bg': '#f5f5f5', 'app-surface': '#fff', 'primary-color': '#1677ff', 'error-color': '#d03050' }, javascriptEnabled: true } } },
})
