const { chromium } = require('playwright');
const mqtt = require('mqtt');

const edgeExecutablePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const baseUrl = 'http://127.0.0.1:8099';
const brokerUrl = 'tcp://127.0.0.1:2883';
const topic = 'IOT/product-load/Data';
let subscriberTimer = null;

async function waitFor(fn, timeout = 20000, interval = 300) {
  const start = Date.now();
  while (Date.now() - start < timeout) {
    const value = await fn();
    if (value) return value;
    await new Promise((resolve) => setTimeout(resolve, interval));
  }
  throw new Error('waitFor timeout');
}

async function run() {
  console.log('step: subscriber connect');
  const subClient = mqtt.connect('mqtt://127.0.0.1:2883');
  const messages = [];

  await new Promise((resolve, reject) => {
    subscriberTimer = setTimeout(() => reject(new Error('subscriber connect timeout')), 5000);
    subClient.on('connect', () => {
      subClient.subscribe(topic, (err) => {
        clearTimeout(subscriberTimer);
        if (err) reject(err);
        else resolve();
      });
    });
    subClient.on('message', (_topic, payload) => {
      try {
        messages.push(JSON.parse(payload.toString()));
      } catch (error) {
        reject(error);
      }
    });
    subClient.on('error', reject);
  });

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

  console.log('step: cleanup existing product-load tasks');
  const existingResponse = await page.request.get(`${baseUrl}/api/tasks`);
  const existingPayload = await existingResponse.json();
  for (const item of existingPayload.items || []) {
    if (item.config && item.config.productId === 'product-load') {
      await page.request.delete(`${baseUrl}/api/tasks/${item.id}`);
    }
  }

  console.log('step: fill form');
  await page.locator('#name').fill('Concurrency Task');
  await page.locator('#productId').fill('product-load');
  await page.locator('#messageType').fill('change');
  await page.locator('#deviceCount').fill('20');
  await page.locator('#messagesPerMinute').fill('600');
  await page.locator('#brokerUrl').fill(brokerUrl);
  await page.locator('#clientIdPrefix').fill('edge-load');
  await page.locator('#topicTemplate').fill('IOT/{productId}/Data');
  await page.locator('#metadata-text').fill(JSON.stringify({
    properties: [
      { id: 'Ua', name: 'A相电压', valueType: { type: 'float', scale: 3 } },
      { id: 'E', name: '组合有功总电能', valueType: { type: 'float', scale: 3 } },
    ],
  }, null, 2));

  await page.locator('#apply-metadata').click();
  await page.waitForTimeout(600);
  console.log('step: save task');
  await page.locator('#save-btn').click();

  console.log('step: fetch task');
  const task = await waitFor(async () => {
    const response = await page.request.get(`${baseUrl}/api/tasks`);
    const payload = await response.json();
    return payload.items.find((item) => item.config.productId === 'product-load');
  });

  console.log('step: start task');
  await page.locator('#start-btn').click();

  console.log('step: wait running');
  const running = await waitFor(async () => {
    const response = await page.request.get(`${baseUrl}/api/tasks/${task.id}/stats`);
    const payload = await response.json();
    return payload.item.runtime.status === 'running' ? payload.item.runtime : null;
  });

  console.log('step: wait messages');
  await waitFor(() => messages.length >= 5, 15000, 500);

  console.log('step: collect runtime');
  await new Promise((resolve) => setTimeout(resolve, 2500));

  const statsResponse = await page.request.get(`${baseUrl}/api/tasks/${task.id}/stats`);
  const statsPayload = await statsResponse.json();
  const runtime = statsPayload.item.runtime;

  if ((runtime.sentTotal || 0) <= 0) {
    throw new Error(`expected sentTotal > 0, got ${runtime.sentTotal}`);
  }

  const byDevice = new Map();
  for (const message of messages) {
    const deviceId = Object.keys(message.data || {})[0];
    const items = (message.data || {})[deviceId] || [];
    const eItem = items.find((item) => item.id === 'E');
    if (!eItem) continue;
    const value = Number(eItem.value);
    if (!byDevice.has(deviceId)) {
      byDevice.set(deviceId, []);
    }
    byDevice.get(deviceId).push(value);
  }

  const target = [...byDevice.values()].find((values) => values.length >= 2);
  if (!target) {
    throw new Error(`expected at least one device to receive repeated E values, got ${JSON.stringify(Object.fromEntries(byDevice))}`);
  }

  for (let i = 1; i < target.length; i++) {
    if (!(target[i] > target[i - 1])) {
      throw new Error(`E values are not increasing: ${target.join(', ')}`);
    }
  }

  console.log('step: stop task');
  await page.locator('#stop-btn').click();
  await waitFor(async () => {
    const response = await page.request.get(`${baseUrl}/api/tasks/${task.id}/stats`);
    const payload = await response.json();
    return payload.item.runtime.status === 'stopped';
  });

  console.log('step: cleanup');
  await page.request.delete(`${baseUrl}/api/tasks/${task.id}`);
  await new Promise((resolve) => subClient.end(true, resolve));
  await browser.close();
  console.log(`Concurrency and E monotonic test passed. runningStatus=${running.status} sentTotal=${runtime.sentTotal} messagesCaptured=${messages.length}`);
  process.exit(0);
}

run().catch((error) => {
  if (subscriberTimer) {
    clearTimeout(subscriberTimer);
  }
  console.error('Concurrency and E monotonic test failed:', error);
  process.exitCode = 1;
});
