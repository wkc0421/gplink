import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: '.',
  testMatch: 'export.spec.ts',
  use: { baseURL: 'http://127.0.0.1:5207', headless: true, browserName: 'chromium' },
})
