import { chromium } from 'playwright';

const base = process.env.GP_TEST_FRONTEND || 'http://127.0.0.1:9100';
const user = process.env.GP_TEST_USER || '';
const password = process.env.GP_TEST_PASSWORD || '';
const browser = await chromium.launch({
  headless: true,
  executablePath: process.env.GP_TEST_BROWSER || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',
});
const context = await browser.newContext({ ignoreHTTPSErrors: true });
const page = await context.newPage();
await page.goto(`${base}/iot/device/ModbusAccess`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(3500);
console.log('initial-url', page.url());
console.log((await page.locator('body').innerText()).slice(0, 1800));
if (page.url().includes('#/login')) {
  const inputs = await page.locator('input').count();
  console.log('input-count', inputs);
  const names = await page.locator('input').evaluateAll?.(els => els.map(e => ({name:e.getAttribute('name'),placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type')}))).catch(()=>[]);
  console.log('inputs', JSON.stringify(names));
  await page.getByLabel('账号').fill(user);
  await page.getByLabel('密码').fill(password);
  await page.getByRole('button', { name: '登 录' }).click();
  await page.waitForTimeout(5000);
  console.log('post-login-url', page.url());
  console.log((await page.locator('body').innerText()).slice(0, 2200));
}
console.log('navigating wizard');
await page.goto(`${base}/#/iot/device/ModbusAccess`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(5000);
console.log('wizard loaded');
console.log('wizard-url', page.url());
console.log((await page.locator('body').innerText()).slice(0, 5000));
await page.screenshot({ path: 'front/tests/e2e/modbus-wizard-initial.png', fullPage: true });
const clientMode = page.getByText('新建 TCP 客户端', { exact: true });
console.log('client-mode-count', await clientMode.count());
await clientMode.click();
await page.waitForTimeout(500);
console.log('client-mode-body', (await page.locator('body').innerText()).slice(-3500));
console.log('form-inputs', JSON.stringify(await page.locator('input').evaluateAll(els => els.map(e => ({placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type'),value:e.value})))));
const stamp = new Date().toISOString().replace(/[-:.TZ]/g, '').slice(0, 14);
await page.getByPlaceholder('例如：Modbus TCP 网络组件').fill(`codex-${stamp}-tcp-client`);
await page.getByPlaceholder('例如：Modbus TCP 接入网关').fill(`codex-${stamp}-gateway`);
await page.getByPlaceholder('设备网关 IP 或域名').fill('192.168.5.48');
const allInputs = page.locator('input');
const inputCount = await allInputs.count();
if (inputCount < 8) throw new Error(`unexpected input count: ${inputCount}`);
await allInputs.nth(7).fill('4000');
const nextButton = page.getByRole('button', { name: '下一步', exact: true });
console.log('next-count', await nextButton.count());
await nextButton.click();
await page.waitForTimeout(1000);
console.log('step2-body', (await page.locator('body').innerText()).slice(-4500));
console.log('step2-inputs', JSON.stringify(await page.locator('input').evaluateAll(els => els.map(e => ({placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type'),value:e.value})))));
const newOptions = page.getByText('新建', { exact: true });
const newOptionCount = await newOptions.count();
console.log('step2-new-count', newOptionCount);
if (newOptionCount < 2) throw new Error(`expected two create options, got ${newOptionCount}`);
await newOptions.nth(0).click();
await newOptions.nth(1).click();
await page.waitForTimeout(300);
console.log('step2-create-body', (await page.locator('body').innerText()).slice(-3800));
console.log('step2-create-inputs', JSON.stringify(await page.locator('input').evaluateAll(els => els.map(e => ({placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type'),value:e.value})))));
const generatedIdInputs = page.getByPlaceholder('留空由系统生成');
const generatedIdCount = await generatedIdInputs.count();
if (generatedIdCount !== 2) throw new Error(`expected two generated ID inputs, got ${generatedIdCount}`);
await generatedIdInputs.nth(0).fill(`codex-${stamp}-gateway-product`);
await page.getByPlaceholder('例如：Modbus 网关产品').fill(`codex-${stamp}-gateway-product`);
await generatedIdInputs.nth(1).fill(`codex-${stamp}-gateway-device`);
await page.getByPlaceholder('例如：Modbus 网关 1').fill(`codex-${stamp}-gateway-device`);
const nextButton2 = page.getByRole('button', { name: '下一步', exact: true });
console.log('next2-count', await nextButton2.count());
await nextButton2.click();
await page.waitForTimeout(1000);
console.log('step3-body', (await page.locator('body').innerText()).slice(-5200));
console.log('step3-inputs', JSON.stringify(await page.locator('input').evaluateAll(els => els.map(e => ({placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type'),value:e.value})))));
const visibleCreateText = page.getByText('新建', { exact: true }).filter({ visible: true });
const visibleCreateCount = await visibleCreateText.count();
console.log('step3-visible-create-count', visibleCreateCount);
if (visibleCreateCount !== 1) throw new Error(`expected one visible create option, got ${visibleCreateCount}`);
await visibleCreateText.click();
await page.waitForTimeout(300);
console.log('step3-create-body', (await page.locator('body').innerText()).slice(-3600));
console.log('step3-create-inputs', JSON.stringify(await page.locator('input').evaluateAll(els => els.map(e => ({placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type'),value:e.value})))));
const slaveProductIds = page.getByPlaceholder('留空由系统生成');
const slaveProductIdCount = await slaveProductIds.count();
if (slaveProductIdCount < 3) throw new Error(`expected slave product ID input, got ${slaveProductIdCount}`);
await slaveProductIds.nth(slaveProductIdCount - 1).fill(`codex-${stamp}-slave-product`);
await page.getByPlaceholder('例如：Modbus 从机产品').fill(`codex-${stamp}-slave-product`);
await page.getByPlaceholder('属性ID').fill('temperature');
await page.getByPlaceholder('名称', { exact: true }).fill('Temperature');
console.log('native-select-count', await page.locator('select').count());
console.log('step3-after-point-body', (await page.locator('body').innerText()).slice(-2600));
const pollText = page.getByText('启用轮询', { exact: true });
console.log('poll-label-count', await pollText.count());
if (await pollText.count() !== 1) throw new Error('polling switch label not found');
const pollSwitch = page.locator('fieldset:visible button[role="switch"]').first();
if (await pollSwitch.count() !== 1) throw new Error('polling switch control not found');
await pollSwitch.click();
await page.waitForTimeout(300);
const nextButton3 = page.getByRole('button', { name: '下一步', exact: true });
console.log('next3-count', await nextButton3.count());
await nextButton3.click();
await page.waitForTimeout(1000);
console.log('step4-body', (await page.locator('body').innerText()).slice(-5000));
console.log('step4-inputs', JSON.stringify(await page.locator('input').evaluateAll(els => els.map(e => ({placeholder:e.getAttribute('placeholder'),type:e.getAttribute('type'),value:e.value})))));
const addSlaveButton = page.getByText('添加从机', { exact: true }).filter({ visible: true });
console.log('add-slave-count', await addSlaveButton.count());
if (await addSlaveButton.count() !== 1) throw new Error('add slave button not found');
for (let i = 0; i < 4; i += 1) {
  await addSlaveButton.click();
  await page.waitForTimeout(120);
}
const visibleSlaveIds = page.locator('input[role="spinbutton"]:visible');
const visibleSlaveIdCount = await visibleSlaveIds.count();
console.log('visible-slave-id-count', visibleSlaveIdCount);
if (visibleSlaveIdCount !== 5) throw new Error(`expected five visible slave IDs, got ${visibleSlaveIdCount}`);
for (let i = 0; i < 5; i += 1) await visibleSlaveIds.nth(i).fill(String(i + 1));
const deviceIdInputs = page.getByPlaceholder('设备ID', { exact: true });
const deviceNameInputs = page.getByPlaceholder('设备名称', { exact: true });
const descriptionInputs = page.getByPlaceholder('说明', { exact: true });
if (await deviceIdInputs.count() !== 5 || await deviceNameInputs.count() !== 5 || await descriptionInputs.count() !== 5) throw new Error('expected five slave device fields');
for (let i = 0; i < 5; i += 1) {
  await deviceIdInputs.nth(i).fill(`codex-${stamp}-slave-${i + 1}`);
  await deviceNameInputs.nth(i).fill(`codex-${stamp}-从机-${i + 1}`);
  await descriptionInputs.nth(i).fill(`Playwright 5-slave closure test slave ${i + 1}`);
}
const nextButton4 = page.getByRole('button', { name: '下一步', exact: true });
console.log('next4-count', await nextButton4.count());
await nextButton4.click();
await page.waitForTimeout(1000);
console.log('step5-body', (await page.locator('body').innerText()).slice(-5200));
const apiResponses = [];
page.on('request', request => {
  if (/\/device-instance$/.test(request.url())) {
    console.log('device-instance-request', request.postData());
  }
});
page.on('response', response => {
  const url = response.url();
  if (/\/network\/config|\/gateway\/device|\/device-product|\/device-instance|\/iot\/device-data/.test(url)) {
    const record = { status: response.status(), url };
    apiResponses.push(record);
    if (response.status() >= 400) response.text().then(text => { record.body = text.slice(0, 1200); }).catch(() => {});
  }
});
const saveButtons = page.getByText('保存并测试', { exact: true }).filter({ visible: true });
const saveButtonCount = await saveButtons.count();
console.log('save-button-count', saveButtonCount);
if (saveButtonCount < 1) throw new Error('save and test button not found');
await saveButtons.nth(saveButtonCount - 1).click();
console.log('save-clicked');
// 保持向导页面打开两分钟，覆盖轮询与连接保持周期。
await page.waitForTimeout(120000);
await page.waitForTimeout(500);
console.log('after-save-body', (await page.locator('body').innerText()).slice(-7000));
console.log('api-responses', JSON.stringify(apiResponses));
await page.screenshot({ path: 'front/tests/e2e/modbus-closure-final.png', fullPage: true });
await browser.close();
