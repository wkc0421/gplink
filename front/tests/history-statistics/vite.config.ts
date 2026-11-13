import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'node:path'
export default defineConfig({
  root: __dirname,
  plugins: [vue()],
  server: { host: '127.0.0.1', port: 5217, strictPort: true, fs: { allow: [path.resolve(__dirname, '../..')] } },
  resolve: { alias: { '@jetlinks-web/core': path.join(__dirname, 'mock.ts'), '@/store': path.join(__dirname, 'mock.ts') } },
})
