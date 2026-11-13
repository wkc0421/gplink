import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import AutoImport from 'unplugin-auto-import/vite'
import path from 'node:path'
const fixture = path.resolve(__dirname, '../property-library-metadata')
const widgets = path.join(__dirname, 'widgets.ts')
export default defineConfig({
  root: fixture,
  plugins: [{ name: 'metadata-realwidgets-boundaries', enforce: 'pre', resolveId(source, importer) {
    if (!importer?.includes('/src/modules/device-manager-ui/')) return
    importer = importer.split('?')[0]
    if (/\/store\/(product|instance)$/.test(source)) return path.join(fixture, 'mock.ts')
    if (/\/api\/(product|instance)$/.test(source)) return path.join(fixture, 'baseApi.ts')
    if (importer.endsWith('/Metadata/Base/Base.vue') && ['./components', './DetailModal', './components/Import', '../../../data'].includes(source)) return path.join(fixture, 'baseWidgets.ts')
    if (importer.endsWith('/Metadata/Base/DefinitionEditor.vue') && source === '../../../data') return path.join(fixture, 'baseWidgets.ts')
    if (importer.endsWith('/Metadata/Base/Base.vue') && source.endsWith('/components/Metadata')) return widgets
    if (importer.endsWith('/Metadata/Base/PropertyEditor.vue') && source === './components') return path.join(fixture, 'baseWidgets.ts')
    if (importer.endsWith('/Metadata/Base/PropertyEditor.vue') && source.endsWith('/components/Metadata')) return widgets
  } }, vue(), AutoImport({ imports: ['vue', 'vue-router'], dts: false })],
  server: { host: '127.0.0.1', port: 5213, strictPort: true, fs: { allow: [path.resolve(fixture, '../..')] } },
  resolve: { alias: {
    '@jetlinks-web/core': path.join(fixture, 'mock.ts'), '@jetlinks-web/utils': path.join(fixture, 'mock.ts'), '@jetlinks-web/components': path.join(fixture, 'mock.ts'),
    '@/locales': path.join(fixture, 'mock.ts'), '@/store': path.join(fixture, 'mock.ts'), '@/utils/comm': path.join(fixture, 'mock.ts'), '@/utils': path.join(fixture, 'mock.ts'),
    '@': path.resolve(fixture, '../../src'),
  } },
  css: { preprocessorOptions: { less: { modifyVars: { 'app-bg': '#f5f5f5', 'app-surface': '#fff', 'app-text-table-header': '#222', 'app-elevated': '#fff', 'app-border': '#ddd', 'app-text-secondary': '#555', 'primary-color': '#1677ff', 'error-color': '#d03050' }, javascriptEnabled: true } } },
})
