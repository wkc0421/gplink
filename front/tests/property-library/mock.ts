// Local fixture only: never calls the test environment or business APIs.
import seed from '../../../back/jetlinks-community-2.10/jetlinks-community-2.10/jetlinks-manager/saas-manager/src/main/resources/property-catalog/seed-v1.json'
import type { PropertyLibrary, PropertyLibraryCategory, PropertyLibraryItem } from '../../src/modules/device-manager-ui/api/propertyLibrary'

const libraries: PropertyLibrary[] = [
  { id: 'standard', code: 'standard', name: '标准库', enabled: true, sortIndex: 0, description: '常用采集项的统一名称与 ID' },
  { ...seed.library, enabled: true, sortIndex: 1, description: '按原始 Excel 整理的项目采集项，共 353 项。' },
  { id: 'project-b', code: 'project_b', name: 'B 项目', enabled: true, sortIndex: 2 },
  { id: 'disabled-library', code: 'disabled_library', name: '停用项目库', enabled: false, sortIndex: 3 },
]
let categories: PropertyLibraryCategory[] = []
let items: PropertyLibraryItem[] = []
const addPath = (libraryId: string, path?: string) => {
  let parentId = ''
  for (const name of (path || '').split('/').filter(Boolean)) {
    let node = categories.find(c => c.libraryId === libraryId && c.parentId === parentId && c.name === name)
    if (!node) { node = { id: `cat-${categories.length}`, libraryId, parentId, name, sortIndex: categories.length }; categories.push(node) }
    parentId = node.id
  }
  return parentId
}
seed.items.forEach((item, index) => items.push({ id: `excel-${index}`, libraryId: seed.library.id, categoryId: addPath(seed.library.id, item.categoryPath), code: item.code, name: item.name, enabled: true, sortIndex: item.sortIndex, remark: 'remark' in item ? String(item.remark) : '', source: item.source }))
seed.items.filter(item => seed.standardCodes.includes(item.code)).forEach((item, index) => items.push({ id: `std-${index}`, libraryId: 'standard', categoryId: addPath('standard', item.categoryPath), code: item.code, name: item.name, enabled: true, sortIndex: item.sortIndex, source: item.source }))
const failures: Record<string, number> = {}
const calls: Array<{ method: string; path: string; body: unknown }> = []
const ok = (result: unknown) => Promise.resolve({ success: true, status: 200, result: structuredClone(result) })
const check = (method: string, path: string, body?: unknown) => {
  calls.push({ method, path, body })
  if (failures[path] > 0) { failures[path]--; throw new Error('模拟请求失败，请重试') }
}
const tree = (libraryId: string, parentId = ''): PropertyLibraryCategory[] => categories.filter(c => c.libraryId === libraryId && !c.parentId === !parentId && (c.parentId || '') === parentId).map(c => ({ ...c, children: tree(libraryId, c.id) }))
const exists = (libraryId: string, code: string, except?: string) => items.find(i => i.libraryId === libraryId && i.code === code && i.id !== except)
const unique = (item: PropertyLibraryItem) => { if (exists(item.libraryId, item.code, item.id)) throw new Error('当前项目库已存在此采集项 ID') }
const listCategories = (id: string) => {
  const ids = new Set([id]); let changed = true
  while (changed) { changed = false; categories.forEach(c => { if (c.parentId && ids.has(c.parentId) && !ids.has(c.id)) { ids.add(c.id); changed = true } }) }
  return ids
}
const addRow = (libraryId: string, row: any, categoryId?: string) => ({ id: crypto.randomUUID(), libraryId, categoryId: categoryId || addPath(libraryId, row.categoryPath), code: row.code, name: row.name, enabled: true, sortIndex: 0, source: row.source, remark: row.remark })
const copy = (libraryId: string, rows: any[], categoryId?: string) => {
  const result = { created: 0, skipped: 0, conflicts: [] as any[] }
  rows.forEach(row => { const old = exists(libraryId, row.code); if (old) { result.skipped++; if (old.name !== row.name) result.conflicts.push({ code: row.code, name: row.name, existingName: old.name }) } else { items.push(addRow(libraryId, row, categoryId)); result.created++ } })
  return result
}

export const request = {
  get(path: string, params: any = {}) {
    check('GET', path, params)
    if (path.endsWith('/_all')) return ok(libraries.filter(l => params.enabled === undefined || l.enabled === params.enabled))
    if (path.endsWith('/_tree')) return ok(tree(params.libraryId))
    throw new Error(`Unexpected fixture GET ${path}`)
  },
  post(path: string, body: any) {
    check('POST', path, body)
    if (path.endsWith('/_query')) {
      const ids = body.includeChildren ? listCategories(body.categoryId) : new Set([body.categoryId])
      const list = items.filter(i => i.libraryId === body.libraryId && (!body.categoryId || (body.categoryId === '__uncategorized__' ? !i.categoryId : ids.has(i.categoryId))) && (body.enabled === undefined || i.enabled === body.enabled) && (!body.keyword || `${i.code} ${i.name}`.toLowerCase().includes(body.keyword.toLowerCase())))
      return ok({ data: list.slice(body.pageIndex * body.pageSize, (body.pageIndex + 1) * body.pageSize), total: list.length, pageIndex: body.pageIndex, pageSize: body.pageSize })
    }
    if (path === '/device/property-library') { const item = { ...body, id: crypto.randomUUID() }; if (libraries.some(l => l.code === item.code)) throw new Error('项目库编码已存在'); libraries.push(item); return ok(item) }
    if (path === '/device/property-library-category') { const item = { ...body, id: crypto.randomUUID() }; categories.push(item); return ok(item) }
    if (path === '/device/property-library-item') { const item = { ...body, id: crypto.randomUUID() }; unique(item); items.push(item); return ok(item) }
    if (path.endsWith('/_batch-category')) { items.forEach(i => { if (body.itemIds.includes(i.id)) i.categoryId = body.categoryId }); return ok(true) }
    if (path.endsWith('/_preview')) return ok({ warnings: ['原始 Excel 中的重复编码需修正后导入。'], rows: [
      { rowNumber: 2, code: 'Ua', name: '新的电压名称', categoryPath: '电参量/电压', errors: [], source: { file: 'test.csv', row: 2 } },
      { rowNumber: 3, code: 'Eqc', name: '组合无功电能', categoryPath: '电能', errors: ['文件内采集项 ID 重复'] },
      { rowNumber: 4, code: 'Eqc', name: 'C相无功电能', categoryPath: '电能', errors: ['文件内采集项 ID 重复'] },
    ] })
    if (path.endsWith('/import/_confirm')) return ok(copy(path.split('/')[3], body.rows))
    if (path.endsWith('/standard/_adopt')) return ok(copy('standard', items.filter(i => body.itemIds.includes(i.id)), body.categoryId))
    throw new Error(`Unexpected fixture POST ${path}`)
  },
  put(path: string, body: any) {
    check('PUT', path, body)
    const id = path.split('/').pop()
    const list = path.startsWith('/device/property-library-item/') ? items : path.startsWith('/device/property-library-category/') ? categories : libraries
    const index = list.findIndex(i => i.id === id)
    if (index < 0) throw new Error('数据不存在')
    const item = { ...list[index], ...body }
    if (path.startsWith('/device/property-library-item/')) unique(item)
    if (path.startsWith('/device/property-library-category/') && listCategories(id!).has(body.parentId)) throw new Error('不能将分类移动到自身或自己的子分类')
    list[index] = item
    return ok(item)
  },
  remove(path: string) {
    check('DELETE', path)
    const id = path.split('/').pop()
    if (path.startsWith('/device/property-library-item/')) items = items.filter(i => i.id !== id)
    else if (path.startsWith('/device/property-library-category/')) { if (categories.some(c => c.parentId === id) || items.some(i => i.categoryId === id)) throw new Error('分类下还有子分类或采集项，不能删除'); categories = categories.filter(c => c.id !== id) }
    else { if (id === 'standard') throw new Error('标准库不能删除'); if (categories.some(c => c.libraryId === id) || items.some(i => i.libraryId === id)) throw new Error('请先删除项目库内的分类和采集项'); libraries.splice(libraries.findIndex(l => l.id === id), 1) }
    return ok(true)
  },
}
;(window as any).__propertyLibraryFixture = { failures, calls, items: () => items, libraries, categories: () => categories }
