import { defineConfig} from '@playwright/test';

export default  defineConfig({
  testDir: './tests/e2e',
  use: {
    baseURL: process.env.PLAYWRIGHT_BASE_URL || 'http://localhost:9200',
    headless: true,
    screenshot: 'only-on-failure',
    trace: 'on-first-retry',
    browserName: 'chromium',
  },

})
