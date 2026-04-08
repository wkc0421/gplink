const { chromium } = require('playwright');

const edgeExecutablePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const baseUrl = 'http://127.0.0.1:8099';
const brokerUrl = 'tcp://127.0.0.1:2883';

async function waitFor(fn, timeout = 15000, interval = 300) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const value = await fn();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
  throw new Error('waitFor timeout');
}

async function run() {
  console.log('step: launch browser');
  const browser = await chromium.launch({
    headless: true,
    executablePath: edgeExecutablePath,
  });

  const page = await browser.newPage();
  page.setDefaultTimeout(15000);
  console.log('step: open page');
  await page.goto(`${baseUrl}/`, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('h1');

  console.log('step: clean existing tasks');
  const existingResponse = await page.request.get(`${baseUrl}/api/tasks`);
  const existingPayload = await existingResponse.json();
  for (const item of existingPayload.items || []) {
    if (item.config && item.config.productId === 'product-e2e') {
      await page.request.delete(`${baseUrl}/api/tasks/${item.id}`);
    }
  }

  console.log('step: fill form');
  await page.locator('#name').fill('E2E Task');
  await page.locator('#productId').fill('product-e2e');
  await page.locator('#messageType').fill('change');
  await page.locator('#deviceCount').fill('5');
  await page.locator('#messagesPerMinute').fill('120');
  await page.locator('#brokerUrl').fill(brokerUrl);
  await page.locator('#clientIdPrefix').fill('edge-smoke');
  await page.locator('#topicTemplate').fill('IOT/{productId}/Data');
  await page.locator('#metadata-text').fill(JSON.stringify({
    properties: [
      { id: 'Ua', name: 'A相电压', valueType: { type: 'float', scale: 3 } },
      { id: 'E', name: '组合有功总电能', valueType: { type: 'float', scale: 3 } },
    ],
  }, null, 2));

  await page.locator('#apply-metadata').click();
  await page.waitForTimeout(700);
  console.log('step: save task');
  await page.locator('#save-btn').click();

  console.log('step: wait task rendered');
  await waitFor(async () => {
    const count = await page.locator('.task-item').count();
    return count > 0;
  });

  console.log('step: fetch created task');
  const tasksResponse = await page.request.get(`${baseUrl}/api/tasks`);
  const tasksPayload = await tasksResponse.json();
  const task = tasksPayload.items.find((item) => item.config.productId === 'product-e2e');
  if (!task) {
    throw new Error('created task not found in API');
  }

  console.log('step: start task');
  await page.locator('#start-btn').click();

  console.log('step: wait runtime running');
  await waitFor(async () => {
    const response = await page.request.get(`${baseUrl}/api/tasks/${task.id}/stats`);
    const payload = await response.json();
    return payload.item.runtime.status === 'running' ? payload.item.runtime : null;
  });

  console.log('step: wait UI running');
  await waitFor(async () => {
    const text = await page.locator('#stat-status').textContent();
    return text && text.includes('running');
  });

  console.log('step: stop task');
  await page.locator('#stop-btn').click();

  console.log('step: wait runtime stopped');
  await waitFor(async () => {
    const response = await page.request.get(`${baseUrl}/api/tasks/${task.id}/stats`);
    const payload = await response.json();
    return payload.item.runtime.status === 'stopped' ? payload.item.runtime : null;
  });

  console.log('step: cleanup task');
  await page.request.delete(`${baseUrl}/api/tasks/${task.id}`);
  await browser.close();
  console.log('Task flow test passed.');
}

run().catch((error) => {
  console.error('Task flow test failed:', error);
  process.exitCode = 1;
});
