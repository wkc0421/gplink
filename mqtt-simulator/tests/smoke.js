const { chromium } = require('playwright');

const edgeExecutablePath = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';

async function run() {
  const browser = await chromium.launch({
    headless: true,
    executablePath: edgeExecutablePath,
  });

  const page = await browser.newPage();
  page.setDefaultTimeout(15000);
  await page.goto('http://127.0.0.1:8099/', { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('h1');

  const title = await page.locator('h1').textContent();
  if (title !== 'MQTT Simulator Studio') {
    throw new Error(`unexpected title: ${title}`);
  }

  await page.locator('#name').fill('Smoke Test Task');
  await page.locator('#productId').fill('product-smoke');
  await page.locator('#messageType').fill('change');
  await page.locator('#brokerUrl').fill('tcp://127.0.0.1:1883');
  await page.locator('#metadata-text').fill(JSON.stringify({
    properties: [
      { id: 'Ua', name: 'A相电压', valueType: { type: 'float', scale: 3 } },
      { id: 'E', name: '组合有功总电能', valueType: { type: 'float', scale: 3 } },
    ],
  }, null, 2));

  await page.locator('#apply-metadata').click();
  await page.waitForTimeout(1000);

  const metadataPreview = await page.locator('#metadata-preview').textContent();
  if (!metadataPreview.includes('Ua')) {
    throw new Error(`metadata preview missing Ua: ${metadataPreview}`);
  }

  const payloadPreview = await page.locator('#payload-preview').textContent();
  if (!payloadPreview.includes('Topic: IOT/product-smoke/Data')) {
    throw new Error(`unexpected topic preview: ${payloadPreview}`);
  }
  if (!payloadPreview.includes('"type":"change"')) {
    throw new Error(`unexpected payload type preview: ${payloadPreview}`);
  }
  if (!payloadPreview.includes('"000001"')) {
    throw new Error(`unexpected device id preview: ${payloadPreview}`);
  }

  await browser.close();
  console.log('Smoke test passed.');
}

run().catch((error) => {
  console.error('Smoke test failed:', error);
  process.exitCode = 1;
});
