import { request } from '@jetlinks-web/core'

export interface PropertyLibrary {
  id: string
  code: string
  name: string
  description?: string
  enabled: boolean
  sortIndex: number
}

export interface PropertyLibraryCategory {
  id: string
  libraryId: string
  parentId?: string
  name: string
  sortIndex: number
  children?: PropertyLibraryCategory[]
}

export interface PropertyLibraryItem {
  id: string
  libraryId: string
  categoryId?: string
  code: string
  name: string
  enabled: boolean
  sortIndex: number
  remark?: string
  source?: Record<string, unknown>
}

export interface PropertyLibraryItemQuery {
  libraryId: string
  categoryId?: string
  includeChildren?: boolean
  keyword?: string
  enabled?: boolean
  pageIndex: number
  pageSize: number
}

export interface PropertyLibraryPager<T> {
  data: T[]
  total: number
  pageIndex?: number
  pageSize?: number
}

export interface PropertyLibraryImportRow {
  rowNumber: number
  code: string
  name: string
  categoryPath?: string
  remark?: string
  source?: Record<string, unknown>
  errors: string[]
}

export interface PropertyLibraryImportPreview {
  rows: PropertyLibraryImportRow[]
  warnings: string[]
}

export interface PropertyLibraryImportResult {
  created: number
  skipped: number
  conflicts: Array<{ code: string; name?: string; existingName?: string; message?: string }>
}

const libraryPath = '/device/property-library'
const categoryPath = '/device/property-library-category'
const itemPath = '/device/property-library-item'
const key = (id: string) => encodeURIComponent(id)

export const getPropertyLibraries = (enabled?: boolean) =>
  request.get<PropertyLibrary[]>(`${libraryPath}/_all`, enabled === undefined ? {} : { enabled })
export const createPropertyLibrary = (data: Omit<PropertyLibrary, 'id'>) =>
  request.post<PropertyLibrary>(libraryPath, data)
export const updatePropertyLibrary = (id: string, data: Partial<PropertyLibrary>) =>
  request.put<PropertyLibrary>(`${libraryPath}/${key(id)}`, data)
export const deletePropertyLibrary = (id: string) => request.remove(`${libraryPath}/${key(id)}`)

export const getPropertyLibraryCategories = (libraryId: string) =>
  request.get<PropertyLibraryCategory[]>(`${categoryPath}/_tree`, { libraryId })
export const createPropertyLibraryCategory = (data: Omit<PropertyLibraryCategory, 'id' | 'children'>) =>
  request.post<PropertyLibraryCategory>(categoryPath, data)
export const updatePropertyLibraryCategory = (id: string, data: Partial<PropertyLibraryCategory>) =>
  request.put<PropertyLibraryCategory>(`${categoryPath}/${key(id)}`, data)
export const deletePropertyLibraryCategory = (id: string) => request.remove(`${categoryPath}/${key(id)}`)

export const queryPropertyLibraryItems = (data: PropertyLibraryItemQuery) =>
  request.post<PropertyLibraryPager<PropertyLibraryItem>>(`${itemPath}/_query`, data)
export const createPropertyLibraryItem = (data: Omit<PropertyLibraryItem, 'id'>) =>
  request.post<PropertyLibraryItem>(itemPath, data)
export const updatePropertyLibraryItem = (id: string, data: Partial<PropertyLibraryItem>) =>
  request.put<PropertyLibraryItem>(`${itemPath}/${key(id)}`, data)
export const deletePropertyLibraryItem = (id: string) => request.remove(`${itemPath}/${key(id)}`)
export const movePropertyLibraryItems = (libraryId: string, itemIds: string[], categoryId?: string) =>
  request.post(`${itemPath}/_batch-category`, { libraryId, itemIds, categoryId })

export const previewPropertyLibraryImport = (libraryId: string, file: File) => {
  const data = new FormData()
  data.append('file', file)
  return request.post<PropertyLibraryImportPreview>(`${libraryPath}/${key(libraryId)}/import/_preview`, data)
}
export const confirmPropertyLibraryImport = (libraryId: string, rows: PropertyLibraryImportRow[]) =>
  request.post<PropertyLibraryImportResult>(`${libraryPath}/${key(libraryId)}/import/_confirm`, { rows })
export const adoptPropertyLibraryItems = (itemIds: string[], categoryId?: string) =>
  request.post<PropertyLibraryImportResult>(`${libraryPath}/standard/_adopt`, { itemIds, categoryId })
