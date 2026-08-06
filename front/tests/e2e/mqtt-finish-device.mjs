import { chromium } from 'playwright'

const name = `codex-mqtt-device-${Date.now().toString(36)}`
const browser = await chromium.launch({ headless: true, executablePath: 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe' })
const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
page.on('response', async response => {
  if (!/\/network\/config|\/gateway\/device|\/device-product|\/device-instance/.test(response.url())) return
  let body = ''
  try { body = await response.text() } catch {}
  console.log('API', response.request().method(), response.status(), response.url(), body.slice(0, 700))
})
page.on('pageerror', error => console.log('PAGE_ERROR', error.message))
const next = async () => {
  const button = page.locator('.content-panel button:visible').last()
  await button.evaluate(element => element.click())
  await page.waitForTimeout(1200)
}

await page.goto('http://127.0.0.1:9100/#/iot/device/MqttClientAccess', { waitUntil: 'domcontentloaded' })
await page.waitForTimeout(3000)
if (await page.locator('input[type="password"]').count()) {
  await page.locator('input[type="text"]').first().fill(process.env.GP_TEST_USER)
  await page.locator('input[type="password"]').first().fill(process.env.GP_TEST_PASSWORD)
  await page.locator('button').first().click()
  await page.waitForTimeout(6000)
}
await page.goto('http://127.0.0.1:9100/#/iot/device/MqttClientAccess', { waitUntil: 'domcontentloaded', timeout: 30000 })
await page.waitForTimeout(9000)
console.log('ROUTE', page.url())
await next()
await next()
const deviceCreate = page.locator('input[type=radio][value=create]').nth(3)
await deviceCreate.evaluate(element => element.click())
await page.waitForTimeout(1000)
const inputs = page.locator('.step-panel').nth(2).locator('input:visible')
console.log('STEP2', await inputs.count(), await inputs.evaluateAll(els => els.map(e => ({ type: e.type, placeholder: e.placeholder, value: e.value }))))
await inputs.nth(2).fill(name)
const enable = page.locator('.step-panel').nth(2).locator('input[type=checkbox]')
if (await enable.isChecked()) await enable.evaluate(element => element.click())
console.log('ENABLE_AFTER_SAVE', await enable.isChecked())
await next()
console.log('REVIEW', (await page.locator('.content-panel').innerText()).slice(0, 3500))
await page.locator('.page-head button').nth(1).evaluate(element => element.click())
await page.waitForTimeout(60000)
console.log('FINAL', (await page.locator('body').innerText()).slice(-6000))
console.log('DEVICE_NAME', name)
await page.screenshot({ path: 'front/tests/e2e/mqtt-finish-device-final.png', fullPage: true })
await browser.close()
