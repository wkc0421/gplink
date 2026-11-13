import { expect, test } from '@playwright/test'
import { categoryOptions, previewRowErrors } from '../../src/modules/device-manager-ui/views/device/PropertyLibrary/utils'
import type { PropertyLibraryImportRow } from '../../src/modules/device-manager-ui/api/propertyLibrary'

test('category parent options block self and descendants, but keep other branches', () => {
  const tree = [{ id: 'a', libraryId: 'l', name: '电参量', sortIndex: 0, children: [{ id: 'b', libraryId: 'l', name: '电压', sortIndex: 0 }] }, { id: 'c', libraryId: 'l', name: '环境', sortIndex: 1 }]
  expect(categoryOptions(tree, 'a')).toEqual([
    { value: 'a', label: '电参量', disabled: true },
    { value: 'b', label: '电参量 / 电压', disabled: true },
    { value: 'c', label: '环境', disabled: false },
  ])
})

test('repairing one duplicate clears the stale server error on both rows', () => {
  const rows: PropertyLibraryImportRow[] = [
    { rowNumber: 3, code: 'Eqc', name: '组合无功电能', errors: ['文件内采集项 ID 重复'] },
    { rowNumber: 4, code: 'Eqc', name: 'C相无功电能', errors: ['文件内采集项 ID 重复'] },
  ]
  expect(previewRowErrors(rows[0], rows)).toContain('预览中存在重复 ID')
  rows[1].code = 'Eqc_phase_c'
  expect(previewRowErrors(rows[0], rows)).toEqual([])
  expect(previewRowErrors(rows[1], rows)).toEqual([])
})

test('invalid identifiers and malformed category paths cannot be confirmed', () => {
  const row = { rowNumber: 2, code: 'a-b', name: 'A相电压', categoryPath: '电参量//电压', errors: [] }
  expect(previewRowErrors(row, [row])).toEqual(['ID 限 64 位字母、数字或下划线', '分类路径需由 1 至 128 字的非空名称组成'])
  row.code = 'Ua'; row.categoryPath = Array(9).fill('电压').join('/')
  expect(previewRowErrors(row, [row])).toContain('分类最多支持 8 级')
})

test('reserved None identifier is rejected regardless of case after preview edits', () => {
  for (const code of ['None', 'none', 'NONE', 'nOnE', ' None ']) {
    const row = { rowNumber: 2, code, name: '自定义采集项', errors: [], changed: true }
    expect(previewRowErrors(row, [row])).toEqual(['采集项 ID 不能为 None'])
  }
  const custom = { rowNumber: 2, code: 'None_value', name: '自定义采集项', errors: [] }
  expect(previewRowErrors(custom, [custom])).toEqual([])
})
