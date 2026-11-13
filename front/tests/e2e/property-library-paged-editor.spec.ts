import { test, expect, type Page } from '@playwright/test'

test.use({ baseURL: 'http://127.0.0.1:5207' })

const items = [
  { code: 'Ua', name: 'A相电压' }, { code: 'Ub', name: 'B相电压' },
  { code: 'Uc', name: 'C相电压' },
  ...Array.from({length: 22}, (_, i) => ({code: `Extra${i}`, name: `扩展${i}`})),
].map(item => ({...item, id: item.code, libraryId: 'standard', categoryId: 'voltage', enabled: true, sortIndex: 0}))

async function setup(page: Page, target: 'product' | 'device') {
  await page.route('**/mock/device/**', async route => {
    const url = new URL(route.request().url())
    const body = route.request().postDataJSON?.()
    let result: any
    if (url.pathname.endsWith('/_all')) result = [{id:'standard',code:'standard',name:'标准库',enabled:true,sortIndex:0}]
    else if (url.pathname.endsWith('/_tree')) result = [{id:'voltage',name:'电压',children:[]}]
    else {
      let filtered = items
      if (body?.keyword) filtered = filtered.filter(item => (item.code + item.name).toLowerCase().includes(body.keyword.toLowerCase()))
      const pageIndex = body?.pageIndex || 0, pageSize = body?.pageSize || 20
      result = {data:filtered.slice(pageIndex * pageSize, (pageIndex + 1) * pageSize), total:filtered.length, pageIndex, pageSize}
    }
    await route.fulfill({json:{success:true,status:200,result}})
  })
  await page.goto(`/?real=${target}`)
  await expect(page.locator('.property-summary-table')).toBeVisible()
}

const row = (page: Page, code: string) => page.locator('.property-summary-table tbody tr').filter({has: page.getByRole('cell', {name:code, exact:true})})
async function configure(page: Page, code: string) {
  await row(page, code).getByRole('button', {name:'编辑'}).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', {name:'数据类型'}).selectOption('float')
  await dialog.getByRole('button', {name:/^(确\s*定|OK)$/i}).click()
  await expect(dialog).toBeHidden()
}

test('summary has no inline editors; custom editor cancels and checks missing/duplicate ID', async ({page}) => {
  await setup(page, 'product')
  await expect(page.locator('.property-summary-table tbody input:not([type="checkbox"]), .property-summary-table tbody select, .property-summary-table tbody textarea')).toHaveCount(0)
  await page.getByRole('button', {name:'自定义采集项'}).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', {name:'采集项名称'}).fill('自定义温度')
  await dialog.getByRole('button', {name:/^(确\s*定|OK)$/i}).click()
  await expect(dialog.getByText('请输入属性 ID')).toBeVisible()
  await dialog.getByRole('combobox', {name:'采集项 ID'}).fill('Ua')
  await dialog.getByRole('button', {name:/^(确\s*定|OK)$/i}).click()
  await expect(dialog.getByText('属性 ID 已存在')).toBeVisible()
  await dialog.getByRole('button', {name:/^(取\s*消|Cancel)$/i}).click()
  await expect(dialog).toBeHidden()
  await expect(page.locator('.property-summary-table')).not.toContainText('自定义温度')
  await row(page, 'Ua').getByRole('button', {name:'编辑'}).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('combobox', {name:'采集项名称'}).fill('临时修改')
  await dialog.getByRole('button', {name:/^(取\s*消|Cancel)$/i}).click()
  await expect(row(page,'Ua')).toContainText('原A相电压')
})

test('library item is draft until configured; failed save retains it and existing protocol data', async ({page}) => {
  await setup(page, 'product')
  const original = await page.getByTestId('store').innerText()
  await page.getByRole('button', {name:'从采集项库添加'}).click()
  const picker = page.getByRole('dialog')
  await expect(picker.getByRole('row').filter({hasText:'A相电压'}).getByRole('checkbox')).toBeDisabled()
  await picker.getByRole('row').filter({hasText:'B相电压'}).getByRole('checkbox').check()
  await picker.getByRole('button', {name:'加入物模型'}).click()
  await expect(row(page,'Ub')).toContainText('待配置')
  await expect(page.getByTestId('store')).toHaveText(original)
  await configure(page, 'Ub')
  await page.getByRole('button', {name:'模拟保存失败'}).click()
  await page.getByRole('button', {name:'保存物模型'}).click()
  await expect(page.getByRole('status')).toContainText('保存失败')
  await expect(row(page,'Ub')).toContainText('B相电压')
  await expect(page.getByTestId('store')).toHaveText(original)
  const metadata = JSON.parse(JSON.parse(await page.getByTestId('payload').innerText()).metadata)
  expect(metadata.properties.find((item:any) => item.id === 'Ua').expands.registerAddr).toBe('42')
  expect(metadata.functions).toEqual([{id:'originalFunction'}])
  await page.getByRole('button', {name:'恢复保存'}).click()
  await page.getByRole('button', {name:'保存物模型'}).click()
  await expect(page.getByRole('status')).toContainText('保存成功')
  await expect(page.getByTestId('store')).toContainText('Ub')
})

test('pagination preserves all selections and blocks save for incomplete off-page items', async ({page}) => {
  await setup(page, 'product')
  await page.getByRole('button', {name:'从采集项库添加'}).click()
  const picker = page.getByRole('dialog')
  for (const code of ['Ub','Uc',...Array.from({length: 9}, (_, i) => `Extra${i}`)]) {
    await picker.getByRole('row').filter({has: page.getByRole('cell', {name:code, exact:true})}).getByRole('checkbox').check()
  }
  await picker.getByRole('button', {name:'加入物模型'}).click()
  await expect(page.locator('.property-summary-table')).toContainText('共 12 项')
  await page.locator('.property-summary-table').getByTitle('2', {exact:true}).click()
  await expect(row(page,'Extra8')).toBeVisible()
  await page.getByRole('button', {name:'保存物模型'}).click()
  await expect(page.getByTestId('payload')).toHaveText('null')
  await expect(page.locator('.property-summary-table')).toContainText('请完善')
})

test('editing protocol fields keeps a cancellable draft and saves confirmed values', async ({page}) => {
  await setup(page, 'product')
  await row(page,'Ua').getByRole('button', {name:'编辑'}).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByText('协议与高级配置').click()
  const address = dialog.locator('.ant-form-item').filter({hasText:'寄存器地址'}).locator('input')
  await expect(address).toHaveValue('42')
  await address.fill('101')
  await dialog.getByRole('button', {name:/^(取\s*消|Cancel)$/i}).click()
  await row(page,'Ua').getByRole('button', {name:'编辑'}).click()
  dialog = page.getByRole('dialog')
  await dialog.getByText('协议与高级配置').click()
  await expect(dialog.locator('.ant-form-item').filter({hasText:'寄存器地址'}).locator('input')).toHaveValue('42')
  await dialog.locator('.ant-form-item').filter({hasText:'寄存器地址'}).locator('input').fill('102')
  await dialog.getByRole('button', {name:/^(确\s*定|OK)$/i}).click()
  await expect(dialog).toBeHidden()
  await page.getByRole('button', {name:'保存物模型'}).click()
  await expect(page.getByRole('status')).toContainText('保存成功')
  const metadata = JSON.parse(JSON.parse(await page.getByTestId('payload').innerText()).metadata)
  expect(metadata.properties.find((item:any) => item.id === 'Ua').expands.registerAddr).toBe('102')
})

test('device inherited item is read-only and only independent properties are submitted', async ({page}) => {
  await setup(page, 'device')
  await expect(row(page,'Ua').getByRole('button',{name:'编辑'})).toBeDisabled()
  await expect(row(page,'Ua').getByRole('button',{name:'删除'})).toBeDisabled()
  await page.getByRole('button', {name:'从采集项库添加'}).click()
  const picker = page.getByRole('dialog')
  await picker.getByRole('row').filter({hasText:'B相电压'}).getByRole('checkbox').check()
  await picker.getByRole('button', {name:'加入物模型'}).click()
  await configure(page, 'Ub')
  await page.getByRole('button', {name:'保存物模型'}).click()
  await expect(page.getByRole('status')).toContainText('保存成功')
  const payload = JSON.parse(await page.getByTestId('payload').innerText())
  expect(payload.properties.map((item:any) => item.id)).toEqual(['Ub'])
  await page.getByRole('button', {name:'切换编辑权限'}).click()
  await expect(page.getByRole('button', {name:'自定义采集项'})).toBeDisabled()
})

test('non-property metadata still uses its existing edit table', async ({page}) => {
  await page.goto('/?real=product&type=functions')
  await expect(page.locator('.property-summary-table')).toHaveCount(0)
  await expect(page.locator('.metadata-edit-table-body')).toBeVisible()
  await expect(page.getByRole('button', {name:'从采集项库添加'})).toHaveCount(0)
  await expect(page.getByRole('button', {name:'自定义采集项'})).toHaveCount(0)
  await expect(page.locator('.metadata-edit-table-body input').first()).toHaveValue('originalFunction')
})
