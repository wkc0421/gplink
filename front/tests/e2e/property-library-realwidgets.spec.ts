import { test, expect } from '@playwright/test'

test.use({ baseURL: 'http://127.0.0.1:5213' })

for (const fullscreen of [false, true]) {
  test(`batch dialog uses real type and unit selectors${fullscreen ? ' in fullscreen' : ''}`, async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.goto('/?real=product')
    const table = page.locator('.property-summary-table')
    await expect(table).toBeVisible()
    if (fullscreen) {
      await page.locator('.extra-right > button').first().click()
      await expect(page.locator('.metadata-edit-table-wrapper')).toHaveClass(/table-full-screen/)
    }
    await table.locator('tbody tr').filter({has: page.getByRole('cell', {name:'Ua', exact:true})}).getByRole('checkbox').check()
    await page.getByRole('button', {name:'批量设置'}).click()
    const dialog = page.getByRole('dialog', {name:'批量设置采集项'})
    await expect(dialog).toBeVisible()
    await dialog.getByRole('checkbox', {name:'设置数据类型'}).check()
    const typeItem = dialog.locator('.ant-form-item').filter({hasText:'设置数据类型'})
    await typeItem.locator('.ant-select-selector').click()
    const floatOption = page.locator('.ant-select-item-option').filter({hasText:'float('})
    await expect(floatOption).toBeVisible()
    await floatOption.click()
    await expect(typeItem.locator('.ant-select-selection-item')).toContainText('float(')
    await dialog.getByRole('checkbox', {name:'设置单位'}).check()
    await expect(typeItem.locator('.ant-select-selection-item')).toContainText('float(')
    const unitItem = dialog.locator('.ant-form-item').filter({hasText:'设置单位'})
    const unit = unitItem.locator('input[role="combobox"]')
    await unit.fill('testVolt')
    await expect(typeItem.locator('.ant-select-selection-item')).toContainText('float(')
    await unit.press('Enter')
    await expect(typeItem.locator('.ant-select-selection-item')).toContainText('float(')
    await expect(unitItem).toContainText('testVolt')
    await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
    await dialog.getByRole('button', {name:/^(确\s*定|OK)$/i}).click()
    await expect(dialog).toBeHidden()
    await expect(table).toContainText('testVolt')
    await expect(table).toContainText('float')
    expect(errors).toEqual([])
  })
}
