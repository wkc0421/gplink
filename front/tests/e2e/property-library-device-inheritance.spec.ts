import { test, expect } from '@playwright/test'

test.use({ baseURL: 'http://127.0.0.1:5207' })

test('missing device metadata displays product attributes as inherited and tracks product changes', async ({ page }) => {
  await page.goto('/?real=device&missingMetadata=1')
  const table = page.locator('.property-summary-table')
  const inherited = table.locator('tbody tr').filter({ has: page.getByRole('cell', { name: 'Ua', exact: true }) })
  await expect(inherited).toContainText('原A相电压')
  await expect(inherited.getByRole('button', { name: '编辑' })).toBeDisabled()
  await expect(inherited.getByRole('button', { name: '删除' })).toBeDisabled()
  await page.getByRole('button', { name: '更新产品物模型' }).click()
  await expect(inherited).toContainText('更新后的继承属性')
  await expect(inherited.getByRole('button', { name: '编辑' })).toBeDisabled()
  await page.getByRole('button', { name: '设置空的已合并物模型' }).click()
  await expect(inherited).toHaveCount(0)
})

test('missing device metadata also falls back for non-property types', async ({ page }) => {
  await page.goto('/?real=device&missingMetadata=1&type=functions')
  const id = page.locator('.metadata-edit-table-body input').first()
  await expect(id).toHaveValue('originalFunction')
  await expect(id).toBeDisabled()
})
