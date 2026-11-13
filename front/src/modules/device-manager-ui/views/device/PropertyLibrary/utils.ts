import type { PropertyLibraryCategory, PropertyLibraryImportRow } from '../../../api/propertyLibrary'

export interface CategoryOption { value: string; label: string; disabled?: boolean }

export const categoryOptions = (categories: PropertyLibraryCategory[], excludedId?: string): CategoryOption[] => {
  const options: CategoryOption[] = []
  const visited = new Set<string>()
  const walk = (nodes: PropertyLibraryCategory[], path: string[], excluded: boolean) => {
    nodes.forEach(node => {
      if (visited.has(node.id)) return
      visited.add(node.id)
      const nextPath = [...path, node.name]
      const disabled = excluded || node.id === excludedId
      options.push({ value: node.id, label: nextPath.join(' / '), disabled })
      walk(node.children || [], nextPath, disabled)
    })
  }
  walk(categories, [], false)
  return options
}

export const importRowErrors = (row: PropertyLibraryImportRow, rows: PropertyLibraryImportRow[]): string[] => {
  const errors: string[] = []
  const code = row.code?.trim() || ''
  const name = row.name?.trim() || ''
  if (!code) errors.push('请填写采集项 ID')
  else if (!/^[a-zA-Z0-9_]+$/.test(code) || code.length > 64) errors.push('ID 限 64 位字母、数字或下划线')
  else if (code.toLowerCase() === 'none') errors.push('采集项 ID 不能为 None')
  if (!name) errors.push('请填写采集项名称')
  else if (name.length > 64) errors.push('名称不能超过 64 个字符')
  if (code && rows.some(other => other !== row && other.code?.trim() === code)) errors.push('预览中存在重复 ID')
  if (row.categoryPath?.trim()) {
    const parts = row.categoryPath.split('/').map(part => part.trim())
    if (parts.length > 8) errors.push('分类最多支持 8 级')
    if (parts.some(part => !part || part.length > 128)) errors.push('分类路径需由 1 至 128 字的非空名称组成')
  }
  return errors
}

export const previewRowErrors = (row: PropertyLibraryImportRow & { changed?: boolean }, rows: PropertyLibraryImportRow[]) => [...new Set([
  ...importRowErrors(row, rows),
  ...(row.changed ? [] : (row.errors || []).filter(error => !/文件内.*重复|预览中.*重复|duplicate/i.test(error))),
])]

export const errorMessage = (error: unknown, fallback = '操作失败，请重试') => {
  const value = error as { response?: { data?: { message?: string } }; message?: string }
  return value?.response?.data?.message || value?.message || fallback
}

export const readResult = <T>(response: { success?: boolean; result?: T; message?: string }): T => {
  if (!response?.success) throw new Error(response?.message || '操作失败，请重试')
  return response.result as T
}
