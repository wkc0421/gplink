import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
const root = __dirname
export default defineConfig({
  root, plugins: [vue()], server: { host: '127.0.0.1', port: 5198, strictPort: true, fs: { allow: [path.resolve(root, '../..')] } },
  resolve: { alias: { '@jetlinks-web/core': path.join(root, 'mock.ts'), '@jetlinks-web/constants': path.join(root, 'mock.ts'), '@/store/menu': path.join(root, 'mock.ts') } },
  css: { preprocessorOptions: { less: { modifyVars: { 'border-color-split': '#ddd', 'background-color-base': '#f5f5f5', 'text-color-secondary': '#666', 'error-color': '#d03050', 'border-radius-base': '4px', 'border-color-base': '#ddd', 'component-background': '#fff', 'text-color': '#222', 'primary-color': '#1677ff' }, javascriptEnabled: true } } },
})
