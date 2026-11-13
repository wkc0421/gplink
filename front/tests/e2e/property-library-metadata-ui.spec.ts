import { test, expect } from '@playwright/test'
test.use({ baseURL: 'http://127.0.0.1:5207' })

const item = (code:string, libraryId='standard', categoryId='voltage') => ({id:`${libraryId}-${code}`,libraryId,categoryId,code,name:({Ua:'A相电压',Ub:'B相电压',Uc:'C相电压',Ia:'A相电流'} as any)[code] || code,enabled:true,sortIndex:0})
test.beforeEach(async ({ page }) => {
  await page.route('**/mock/device/**', async route => {
    const url = new URL(route.request().url()), body = route.request().postDataJSON?.()
    let result:any
    if(url.pathname.endsWith('/_all')) result=[{id:'standard',code:'standard',name:'标准库',enabled:true,sortIndex:0},{id:'project-a',code:'project-a',name:'A 项目',enabled:true,sortIndex:1}]
    else if(url.pathname.endsWith('/_tree')) result=[{id:'electrical',name:'电参量',children:[{id:'voltage',name:'电压'},{id:'current',name:'电流'}]}]
    else {
      let data=body.libraryId==='project-a'?[item('ProjectUa','project-a')]:[item('Ua'),item('Ub'),item('Uc'),item('Ia','standard','current'),...Array.from({length:22},(_,i)=>item(`Extra${i}`))]
      if(body.categoryId && body.categoryId!=='electrical') data=data.filter(value=>value.categoryId===body.categoryId)
      if(body.keyword) data=data.filter(value=>(value.name+value.code).toLowerCase().includes(body.keyword.toLowerCase()))
      result={data:data.slice(body.pageIndex*body.pageSize,(body.pageIndex+1)*body.pageSize),total:data.length,pageIndex:body.pageIndex,pageSize:body.pageSize}
    }
    await route.fulfill({json:{success:true,result}})
  })
  await page.goto('/')
})

test('multi-selection survives categories and pages; switching library clears selection', async ({page})=>{
  await page.getByRole('button',{name:'从采集项库添加',exact:true}).click()
  const dialog=page.getByRole('dialog')
  const existing=dialog.getByRole('row').filter({hasText:'A相电压'})
  await expect(existing.getByRole('checkbox')).toBeDisabled()
  await dialog.getByRole('row').filter({hasText:'B相电压'}).getByRole('checkbox').check()
  await dialog.getByTitle('2', {exact:true}).click()
  await dialog.getByRole('row').filter({hasText:'Extra21'}).getByRole('checkbox').check()
  await expect(dialog.getByText('已选 2 项', {exact:false})).toBeVisible()
  await dialog.getByText('电参量',{exact:true}).click()
  await expect(dialog.getByText('已选 2 项', {exact:false})).toBeVisible()
  await dialog.getByRole('button',{name:'加入物模型'}).click()
  await expect(page.getByTestId('draft')).toContainText('Ub')
  await expect(page.getByTestId('draft')).toContainText('Extra21')
  await expect(page.getByTestId('saved')).toHaveText('[]')
  await page.getByRole('button',{name:'从采集项库添加',exact:true}).click()
  await dialog.getByRole('row').filter({hasText:'C相电压'}).getByRole('checkbox').check()
  await dialog.locator('.ant-select[aria-label="选择项目库"]').click()
  await page.getByTitle('A 项目',{exact:true}).click()
  await expect(dialog.getByText('已选 0 项',{exact:false})).toBeVisible()
})

test('inline selection links name and ID, supports custom text, inherited disabled and save failure retains draft', async ({page})=>{
  const names=page.getByRole('combobox',{name:'采集项名称'}), ids=page.getByRole('combobox',{name:'采集项 ID'})
  await expect(names.nth(0)).toBeDisabled(); await expect(ids.nth(0)).toBeDisabled()
  await names.nth(1).fill('B相')
  await page.getByText('B相电压 · Ub',{exact:true}).click()
  await expect(names.nth(1)).toHaveValue('B相电压'); await expect(ids.nth(1)).toHaveValue('Ub')
  await names.nth(2).fill('自定义温度'); await ids.nth(2).fill('custom_temp')
  await page.getByRole('button',{name:'模拟保存失败'}).click()
  await page.getByRole('button',{name:'保存草稿'}).click()
  await expect(page.getByRole('status')).toContainText('保存失败')
  await expect(names.nth(2)).toHaveValue('自定义温度')
  await expect(page.getByTestId('saved')).toHaveText('[]')
})

test('half-filled rows fail and hidden fields remain validated after display toggles',async({page})=>{
  const names=page.getByRole('combobox',{name:'采集项名称'}),ids=page.getByRole('combobox',{name:'采集项 ID'})
  await names.nth(1).fill('自定义温度')
  await page.getByRole('button',{name:'保存草稿'}).click()
  await expect(page.getByRole('status')).toContainText('ID 必填')
  await ids.nth(1).fill('Temp')
  await page.getByRole('button',{name:'完整字段'}).click()
  await expect(page.getByRole('textbox',{name:'来源'}).nth(1)).toBeVisible()
  await page.getByRole('button',{name:'精简字段'}).click()
  await page.getByRole('button',{name:'清空隐藏来源'}).click()
  await page.getByRole('button',{name:'保存草稿'}).click()
  await expect(page.getByRole('status')).toContainText('来源必填')
})

