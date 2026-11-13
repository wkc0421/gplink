import type { PropertyLibraryItem } from '../../api/propertyLibrary'

type PropertyDraft = Record<string, any>

/** A blank, automatically appended row is not a draft until a user supplies data. */
export function hasPropertyDraftContent(row: PropertyDraft): boolean {
  if (row.id || row.name || row.sortsIndex !== undefined && row.sortsIndex !== null) return true
  const valueType = row.valueType || {}
  if (Object.entries(valueType).some(([key, value]) => key === 'expands'
    ? value && Object.keys(value as object).length > 0
    : value !== undefined && value !== null && value !== '')) return true
  return Object.entries(row.expands || {}).some(([key, value]) => {
    if (['group', 'groupId', 'groupName', 'isProduct'].includes(key)) return false
    if (key === 'source') return value !== undefined && value !== 'device'
    if (key === 'type') return JSON.stringify(value) !== JSON.stringify(['report']) && value !== undefined
    return value !== undefined && value !== null && value !== ''
  })
}

/** Choosing a catalog entry is an explicit edit of these two fields only. */
export function applyPropertyLibraryItem(
  row: PropertyDraft,
  item: Pick<PropertyLibraryItem, 'code' | 'name'>,
  rows: PropertyDraft[],
  blocked = false,
): boolean {
  if (blocked || row.expands?.isProduct) return false
  if (rows.some(other => other !== row && other.id === item.code)) {
    throw new Error(`采集项 ID ${item.code} 已存在，请选择其他采集项`)
  }
  row.id = item.code
  row.name = item.name
  return true
}

export function uniquePropertyLibraryItems(items: PropertyLibraryItem[], rows: PropertyDraft[]) {
  const codes = new Set(rows.map(row => row.id).filter(Boolean))
  const accepted: PropertyLibraryItem[] = []
  const skipped: string[] = []
  for (const item of items) {
    if (!item.code || item.enabled === false || codes.has(item.code)) {
      skipped.push(item.code)
      continue
    }
    codes.add(item.code)
    accepted.push(item)
  }
  return { accepted, skipped }
}

export const basicPropertyColumnKeys = ['name', 'id', 'valueType', 'unit', 'operationType', 'other']

export function propertyDisplayColumns<T extends { dataIndex?: any }>(columns: T[], complete: boolean): T[] {
  if (complete) return columns
  return basicPropertyColumnKeys.flatMap(key => columns.filter(column => column.dataIndex === key))
}
