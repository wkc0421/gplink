import { expect, test } from '@playwright/test'
import { effectScope, nextTick, ref } from 'vue'
import { applyPropertyLibraryItem, hasPropertyDraftContent, propertyDisplayColumns, uniquePropertyLibraryItems } from '../../src/modules/device-manager-ui/components/PropertyLibrary/metadataCatalog'
import { useValidate } from '../../src/modules/device-manager-ui/components/Metadata/hooks/useValidate'

const blank = () => ({ id: undefined, name: undefined, valueType: { type: undefined, expands: {} }, expands: { source: 'device', type: ['report'], groupId: 'g', groupName: '默认分组' } })
const item = (code: string, name = code): any => ({ id: `library-${code}`, libraryId: 'standard', code, name, enabled: true, sortIndex: 0 })

test('catalog selection changes only name/id and preserves device configuration', () => {
  const row: any = { ...blank(), id: 'old', name: '旧项', valueType: { type: 'float', unit: 'V', scale: 2 }, expands: { registerAddr: '42', parseMethod: 10, formula: '$value * 2', virtualRule: { type: 'script' } } }
  const settings = JSON.stringify([row.expands, row.valueType])
  expect(applyPropertyLibraryItem(row, item('Ub', 'B相电压'), [row])).toBe(true)
  expect([row.id, row.name]).toEqual(['Ub', 'B相电压'])
  expect(JSON.stringify([row.expands, row.valueType])).toBe(settings)
  expect(() => applyPropertyLibraryItem(row, item('Ua'), [row, { id: 'Ua' }])).toThrow('已存在')
  expect(row.id).toBe('Ub')
  expect(applyPropertyLibraryItem(row, item('Uc'), [row], true)).toBe(false)
  row.expands.isProduct = true
  expect(applyPropertyLibraryItem(row, item('Uc'), [row])).toBe(false)
})

test('batch selection prevents duplicates even when a draft changes after opening picker', () => {
  const original = [{ id: 'Ua', name: '原名称' }]
  const result = uniquePropertyLibraryItems([item('Ua'), item('Ub'), item('Ub'), { ...item('Uc'), enabled: false }], original)
  expect(result.accepted.map(value => value.code)).toEqual(['Ub'])
  expect(result.skipped).toEqual(['Ua', 'Ub', 'Uc'])
  expect(original).toEqual([{ id: 'Ua', name: '原名称' }])
})

test('blank padding ignored but name-only or configured rows must validate', () => {
  expect(hasPropertyDraftContent(blank())).toBe(false)
  expect(hasPropertyDraftContent({ ...blank(), name: '只填名称' })).toBe(true)
  expect(hasPropertyDraftContent({ ...blank(), valueType: { type: 'float' } })).toBe(true)
  expect(hasPropertyDraftContent({ ...blank(), expands: { ...blank().expands, registerAddr: '0' } })).toBe(true)
})

test('full validation catches hidden configuration and half-filled rows while skipping padding', async () => {
  const scope = effectScope()
  const rows = ref<any[]>([blank(), { ...blank(), name: '温度' }])
  const columns = ref<any[]>([
    { dataIndex: 'id', form: { rules: [{ required: true, message: 'ID 必填' }] } },
    { dataIndex: 'name', form: { rules: [{ required: true, message: '名称必填' }] } },
    { dataIndex: 'source', form: { field: ['expands', 'source'], rules: [{ required: true, message: '来源必填' }] } },
  ])
  const validator = scope.run(() => useValidate(rows, () => columns.value, 'id', { shouldValidateRow: hasPropertyDraftContent }))!
  expect(propertyDisplayColumns(columns.value, false).map(column => column.dataIndex)).toEqual(['name', 'id'])
  await expect(validator.validate()).rejects.toEqual(expect.arrayContaining([expect.arrayContaining([expect.objectContaining({ field: 'id' })])]))
  rows.value[1].id = 'Temp'; rows.value[1].expands.source = undefined
  await expect(validator.validate()).rejects.toEqual(expect.arrayContaining([expect.arrayContaining([expect.objectContaining({ field: 'expands.source' })])]))
  rows.value[1].expands.source = 'device'
  await expect(validator.validate()).resolves.toHaveLength(1)
  columns.value = [...columns.value, { dataIndex: 'unit', form: { rules: [{ required: true, message: '单位必填' }] } }]
  await nextTick()
  await expect(validator.validate()).rejects.toEqual(expect.arrayContaining([expect.arrayContaining([expect.objectContaining({ field: 'unit' })])]))
  scope.stop()
})
