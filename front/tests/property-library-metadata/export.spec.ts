import { test, expect, type Page } from '@playwright/test'

const exportUrl = '**/mock/v1/product/test-product/property-metadata/properties.xlsx'
const workbook = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x01, 0x02])

async function openProduct(page: Page) {
  await page.goto('/?real=product')
  await expect(page.locator('.property-summary-table')).toBeVisible()
}

test('exports saved product properties using the template download', async ({ page }) => {
  let requests = 0
  await page.route(exportUrl, async route => {
    requests++
    expect(route.request().method()).toBe('GET')
    await route.fulfill({ status: 200, body: workbook, contentType: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' })
  })
  await openProduct(page)
  const button = page.getByRole('button', { name: '导出 Excel' })
  await button.hover()
  await expect(page.getByRole('tooltip')).toContainText('仅导出已保存属性的导入模板 13 个字段，重新导入可能丢失其他属性配置')
  const downloadPromise = page.waitForEvent('download')
  await button.click()
  const download = await downloadPromise
  expect(download.suggestedFilename()).toBe('产品物模型.xlsx')
  expect(requests).toBe(1)
  const stream = await download.createReadStream()
  expect(stream).not.toBeNull()
  const chunks: Buffer[] = []
  for await (const chunk of stream!) chunks.push(Buffer.from(chunk))
  expect(Buffer.concat(chunks)).toEqual(workbook)
})

test('warns about unsaved draft and still downloads saved data', async ({ page }) => {
  let requests = 0
  await page.route(exportUrl, async route => {
    requests++
    await route.fulfill({ status: 200, body: workbook })
  })
  await openProduct(page)
  const original = await page.getByTestId('store').innerText()
  await page.locator('.property-summary-table tbody tr').filter({ hasText: 'Ua' }).getByRole('button', { name: '编辑' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', { name: '采集项名称' }).fill('未保存修改')
  await dialog.getByRole('button', { name: /^(确\s*定|OK)$/i }).click()
  await expect(dialog).toBeHidden()
  const downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel' }).click()
  await downloadPromise
  await expect(page.getByRole('status')).toContainText('存在未保存的修改')
  await expect(page.getByTestId('store')).toHaveText(original)
  expect(requests).toBe(1)
})

test('view mode can export without edit permission', async ({ page }) => {
  await page.route(exportUrl, route => route.fulfill({ status: 200, body: workbook }))
  await openProduct(page)
  await page.getByRole('button', { name: '切换编辑权限' }).click()
  await expect(page.getByRole('button', { name: '自定义采集项' })).toBeDisabled()
  const button = page.getByRole('button', { name: '导出 Excel' })
  await expect(button).toBeEnabled()
  const downloadPromise = page.waitForEvent('download')
  await button.click()
  expect((await downloadPromise).suggestedFilename()).toBe('产品物模型.xlsx')
})

test('search and pagination do not restrict the product export request', async ({ page }) => {
  const urls: string[] = []
  await page.route(exportUrl, async route => {
    urls.push(route.request().url())
    await route.fulfill({ status: 200, body: workbook })
  })
  await page.goto('/?real=product&exportSeed=1')
  const table = page.locator('.property-summary-table')
  await expect(table).toContainText('共 13 项')
  await table.getByTitle('2', { exact: true }).click()
  await expect(table).toContainText('Export11')
  let downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel' }).click()
  await downloadPromise
  await page.getByLabel('搜索属性名称或 ID').fill('Export1')
  await expect(table).not.toContainText('Export0')
  downloadPromise = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出 Excel' }).click()
  await downloadPromise
  expect(urls).toHaveLength(2)
  for (const url of urls) expect(new URL(url).pathname + new URL(url).search).toBe('/mock/v1/product/test-product/property-metadata/properties.xlsx')
})

test('reports failed export without starting a download', async ({ page }) => {
  await page.route(exportUrl, route => route.fulfill({ status: 403, json: { message: '没有导出权限' } }))
  await openProduct(page)
  const downloads: string[] = []
  page.on('download', download => downloads.push(download.suggestedFilename()))
  await page.getByRole('button', { name: '导出 Excel' }).click()
  await expect(page.getByRole('status')).toContainText('没有导出权限')
  expect(downloads).toEqual([])
  await expect(page.getByRole('button', { name: '导出 Excel' })).toBeEnabled()
})

test('reports a JSON error returned with HTTP 200 without downloading', async ({ page }) => {
  await page.route(exportUrl, route => route.fulfill({ status: 200, json: { message: '导出数据不可用' } }))
  await openProduct(page)
  const downloads: string[] = []
  page.on('download', download => downloads.push(download.suggestedFilename()))
  await page.getByRole('button', { name: '导出 Excel' }).click()
  await expect(page.getByRole('status')).toContainText('导出数据不可用')
  expect(downloads).toEqual([])
})

test('does not show export for devices or product functions', async ({ page }) => {
  await page.goto('/?real=device')
  await expect(page.locator('.property-summary-table')).toBeVisible()
  await expect(page.getByRole('button', { name: '导出 Excel' })).toHaveCount(0)
  await page.goto('/?real=product&type=functions')
  await expect(page.getByRole('button', { name: '导出 Excel' })).toHaveCount(0)
})
