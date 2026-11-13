import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
const root = __dirname
export default defineConfig({
  root,
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 5201, strictPort: true, fs: { allow: [path.resolve(root, '../../..')] } },
  resolve: { alias: { '@jetlinks-web/core': path.join(root, 'mock.ts') } },
})
