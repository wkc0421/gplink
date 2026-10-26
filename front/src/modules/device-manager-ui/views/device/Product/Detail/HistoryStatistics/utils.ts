import type { HistoryStatisticsConfig } from '../../../../../api/historyStatistics'

export interface NumericProperty {
  id: string
  name: string
  type: string
}

export interface HistoryStatisticsRow extends HistoryStatisticsConfig {
  name: string
  type: string
  available: boolean
  configured: boolean
}

export const NUMERIC_PROPERTY_TYPES = new Set([
  'int', 'long', 'float', 'double', 'decimal', 'bigDecimal', 'number',
])

export function numericProperties(metadata: unknown): NumericProperty[] {
  let parsed: any
  try {
    parsed = typeof metadata === 'string' ? JSON.parse(metadata) : metadata
  } catch {
    return []
  }
  if (!Array.isArray(parsed?.properties)) return []
  const seen = new Set<string>()
  return parsed.properties.flatMap((property: any) => {
    const id = typeof property?.id === 'string' ? property.id : ''
    const type = property?.valueType?.type
    if (!id || seen.has(id) || !NUMERIC_PROPERTY_TYPES.has(type)) return []
    seen.add(id)
    return [{
      id,
      name: property.name || id,
      type,
    }]
  })
}

export function historyStatisticsRows(
  properties: NumericProperty[],
  configs: HistoryStatisticsConfig[],
): HistoryStatisticsRow[] {
  const propertyMap = new Map(properties.map(property => [property.id, property]))
  const configMap = new Map(configs.map(config => [config.propertyId, config]))
  const configuredRows = configs.map(config => {
    const property = propertyMap.get(config.propertyId)
    return {
      ...config,
      name: property?.name || config.propertyId,
      type: property?.type || '—',
      available: Boolean(property),
      configured: true,
    }
  })
  const unconfiguredRows = properties
    .filter(property => !configMap.has(property.id))
    .map(property => ({
      propertyId: property.id,
      enabled: false,
      name: property.name,
      type: property.type,
      available: true,
      configured: false,
    }))
  return [...configuredRows, ...unconfiguredRows]
}

const shanghaiFormatter = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Shanghai',
  year: 'numeric', month: '2-digit', day: '2-digit',
  hour: '2-digit', minute: '2-digit', second: '2-digit',
  hourCycle: 'h23',
})

export function formatShanghaiTime(value: number | string | null | undefined): string {
  if (value === null || value === undefined || value === '') return '—'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return '—'
  const parts = Object.fromEntries(
    shanghaiFormatter.formatToParts(date).map(part => [part.type, part.value]),
  )
  return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`
}

/** Treat the date-picker text as an Asia/Shanghai wall-clock time, not browser local time. */
export function parseShanghaiTime(value: string): number | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2}) (\d{2}):(\d{2}):(\d{2})$/.exec(value)
  if (!match) return undefined
  const [, year, month, day, hour, minute, second] = match.map(Number)
  const timestamp = Date.UTC(year, month - 1, day, hour - 8, minute, second)
  return formatShanghaiTime(timestamp) === value ? timestamp : undefined
}

export function validateRebuildRange(
  range: [string, string] | undefined,
  now = Date.now(),
): { from: number; to: number } | undefined {
  if (!range?.[0] || !range?.[1]) return undefined
  const from = parseShanghaiTime(range[0])
  const to = parseShanghaiTime(range[1])
  if (from === undefined || to === undefined || from >= to || to > now) return undefined
  return { from, to }
}
