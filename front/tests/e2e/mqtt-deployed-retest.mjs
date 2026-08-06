import { chromium } from 'playwright'

const stamp = Date.now().toString(36)
const names = {
  network: `codex-mqtt-deployed-network-${stamp}`,
  access: `codex-mqtt-deployed-access-${stamp}`,
  product: `codex-mqtt-deployed-product-${stamp}`,
  device: `codex-mqtt-deployed-device-${stamp}`,
  clientId: `codex-deployed-client-${stamp}`,
}
const url = 'http://192.168.5.19:18088/#/iot/device/MqttClientAccess'
const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' })
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
const apiEvents = []
page.on('response', async response => {
  if (!/\/network\/config|\/gateway\/device|\/device-product|\/device-instance/.test(response.url())) return
  let body = ''
  try { body = await response.text() } catch {}
  apiEvents.push({ method: response.request().method(), url: response.url(), status: response.status(), body: body.slice(0, 1200) })
  console.log('API', response.request().method(), response.status(), response.url(), body.slice(0, 500))
})
page.on('pageerror', error => console.log('PAGE_ERROR', error.message))

const clickRadio = async index => {
  const radio = page.locator('input[type=radio][value=create]').nth(index)
  await radio.evaluate(element => element.click())
  await page.waitForTimeout(800)
}
const clickLastContentButton = async () => {
  const button = page.locator('.content-panel button:visible').last()
  await button.evaluate(element => element.click())
  await page.waitForTimeout(1000)
}

await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(3000)
if (await page.locator('input[type="password"]').count()) {
  await page.locator('input[type="text"]').first().fill(process.env.GP_TEST_USER)
  await page.locator('input[type="password"]').first().fill(process.env.GP_TEST_PASSWORD)
  await page.locator('button').first().click()
  await page.waitForTimeout(6000)
}
await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(9000)
if (!page.url().includes('/iot/device/MqttClientAccess')) throw new Error(`wizard route not loaded: ${page.url()}`)

await clickRadio(0)
let inputs = page.locator('.step-panel').nth(0).locator('input:visible')
console.log('STEP0_INPUTS', await inputs.count())
await inputs.nth(0).fill(names.network)
await inputs.nth(1).fill(names.clientId)
await inputs.nth(2).fill('192.168.5.19')
await inputs.nth(3).fill('1883')
await inputs.nth(4).fill('gplink_msh3to7v')
await inputs.nth(5).fill('gplink_msh3to7v')
await inputs.nth(6).fill('')
await inputs.nth(7).fill('8192')
await inputs.nth(7).press('Tab')
await clickLastContentButton()

await clickRadio(1)
inputs = page.locator('.step-panel').nth(1).locator('input:visible')
console.log('STEP1_INPUTS', await inputs.count())
await inputs.nth(0).fill(names.access)
const protocolSelect = page.locator('.step-panel').nth(1).locator('.ant-select').last()
if (await protocolSelect.count() && !(await protocolSelect.locator('.ant-select-selection-item').count())) {
  await protocolSelect.evaluate(element => element.click())
  await page.waitForTimeout(300)
  const option = page.locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option').first()
  if (await option.count()) await option.evaluate(element => element.click())
}
await clickLastContentButton()

await clickRadio(2)
inputs = page.locator('.step-panel').nth(2).locator('input:visible')
console.log('STEP2_INPUTS', await inputs.count())
await inputs.nth(0).fill('')
await inputs.nth(1).fill(names.product)
await inputs.nth(2).fill('')
await inputs.nth(3).fill(names.device)
await clickLastContentButton()

console.log('REVIEW', (await page.locator('.content-panel').innerText()).slice(0, 5000))
await page.locator('.page-head button').nth(1).evaluate(element => element.click())
await page.waitForTimeout(45000)
console.log('FINAL_URL', page.url())
console.log('FINAL_BODY', (await page.locator('body').innerText()).slice(-7000))
await page.screenshot({ path: 'front/tests/e2e/mqtt-deployed-retest.png', fullPage: true })
console.log('NAMES', JSON.stringify(names))
console.log('API_EVENTS', JSON.stringify(apiEvents, null, 2))
await browser.close()
