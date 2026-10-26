import { expect, test } from '@playwright/test'
import {
  formatShanghaiTime,
  historyStatisticsRows,
  numericProperties,
  parseShanghaiTime,
  validateRebuildRange,
} from '../../src/modules/device-manager-ui/views/device/Product/Detail/HistoryStatistics/utils'

test.describe('product history statistics helpers', () => {
  test('offers numeric product properties without model markers and never enables them implicitly', () => {
    const properties = numericProperties(JSON.stringify({
      properties: [
        { id: 'energy', name: '累计电量', valueType: { type: 'double' } },
        { id: 'meter', name: '表码', valueType: { type: 'long' } },
        { id: 'instant', name: '瞬时功率', valueType: { type: 'float' } },
        { id: 'state', name: '状态', valueType: { type: 'string' } },
      ],
    }))
    expect(properties.map(property => property.id)).toEqual(['energy', 'meter', 'instant'])

    const rows = historyStatisticsRows(properties, [{
      propertyId: 'energy', enabled: true, startTime: 1,
    }])
    expect(rows).toEqual(expect.arrayContaining([
      expect.objectContaining({ propertyId: 'energy', configured: true, enabled: true }),
      expect.objectContaining({ propertyId: 'meter', configured: false, enabled: false }),
      expect.objectContaining({ propertyId: 'instant', configured: false, enabled: false }),
    ]))
    expect(rows).toHaveLength(3)
  })

  test('ignores legacy model markers when listing numeric configurations', () => {
    const properties = numericProperties({ properties: [
      { id: 'legacy', valueType: { type: 'double' } },
      { id: 'instant', valueType: { type: 'float' }, expands: { cumulativeCounter: false } },
    ] })
    const rows = historyStatisticsRows(properties, [
      { propertyId: 'legacy', enabled: true },
      { propertyId: 'instant', enabled: false },
    ])
    expect(rows.find(row => row.propertyId === 'legacy')?.available).toBe(true)
    expect(rows.find(row => row.propertyId === 'instant')?.available).toBe(true)
    expect(historyStatisticsRows(properties, []).map(row => row.propertyId)).toEqual(['legacy', 'instant'])
  })

  test('keeps deleted or nonnumeric configured properties visible but unavailable', () => {
    const rows = historyStatisticsRows([], [{ propertyId: 'old', enabled: false }])
    expect(rows).toEqual([
      expect.objectContaining({ propertyId: 'old', available: false, configured: true }),
    ])
    expect(numericProperties('{broken')).toEqual([])
  })

  test('parses the selected wall-clock time in Asia/Shanghai independent of local zone', () => {
    expect(parseShanghaiTime('2026-02-28 00:00:00')).toBe(Date.UTC(2026, 1, 27, 16))
    expect(formatShanghaiTime(Date.UTC(2026, 1, 27, 16))).toBe('2026-02-28 00:00:00')
    expect(parseShanghaiTime('2026-02-30 00:00:00')).toBeUndefined()
    expect(parseShanghaiTime('2025-02-29 00:00:00')).toBeUndefined()
    expect(parseShanghaiTime('2024-02-29 00:00:00')).toBe(Date.UTC(2024, 1, 28, 16))
  })

  test('rejects reversed and future rebuild ranges', () => {
    const now = Date.UTC(2026, 8, 23)
    expect(validateRebuildRange([
      '2026-09-20 00:00:00', '2026-09-21 00:00:00',
    ], now)).toEqual({
      from: Date.UTC(2026, 8, 19, 16),
      to: Date.UTC(2026, 8, 20, 16),
    })
    expect(validateRebuildRange([
      '2026-09-21 00:00:00', '2026-09-20 00:00:00',
    ], now)).toBeUndefined()
    expect(validateRebuildRange([
      '2026-09-20 00:00:00', '2026-09-24 00:00:00',
    ], now)).toBeUndefined()
  })
})
