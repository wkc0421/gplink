/**
 * Shared dark-theme presentation defaults for ECharts.
 *
 * This helper only augments visual options (axes, labels, legends and
 * tooltips). Series data, queries and interaction configuration are left
 * untouched so existing charts keep their current behaviour.
 */
const textColor = '#C4D0DE'
const axisColor = '#6F87A3'
const splitColor = 'rgba(170, 184, 202, 0.22)'

const mergeAxis = (axis: any) => ({
  ...axis,
  axisLabel: {
    color: textColor,
    fontSize: 11,
    ...(axis?.axisLabel || {}),
  },
  axisLine: {
    ...(axis?.axisLine || {}),
    lineStyle: {
      color: axis?.axisLine?.lineStyle?.color || axisColor,
      ...(axis?.axisLine?.lineStyle || {}),
    },
  },
  splitLine: {
    ...(axis?.splitLine || {}),
    lineStyle: {
      color: axis?.splitLine?.lineStyle?.color || splitColor,
      ...(axis?.splitLine?.lineStyle || {}),
    },
  },
})

/** Apply dark visual defaults while preserving caller-provided values. */
export const withDarkEchartsTheme = (options: any = {}) => ({
  ...options,
  textStyle: {
    color: textColor,
    fontFamily: 'Segoe UI, Microsoft YaHei, PingFang SC, sans-serif',
    ...(options.textStyle || {}),
  },
  xAxis: Array.isArray(options.xAxis)
    ? options.xAxis.map(mergeAxis)
    : options.xAxis
      ? mergeAxis(options.xAxis)
      : options.xAxis,
  yAxis: Array.isArray(options.yAxis)
    ? options.yAxis.map(mergeAxis)
    : options.yAxis
      ? mergeAxis(options.yAxis)
      : options.yAxis,
  legend: options.legend
    ? {
        ...options.legend,
        textStyle: {
          color: textColor,
          ...(options.legend.textStyle || {}),
        },
      }
    : options.legend,
  tooltip: options.tooltip
    ? {
        ...options.tooltip,
        backgroundColor: options.tooltip.backgroundColor || '#13243A',
        borderColor: options.tooltip.borderColor || '#4B6F95',
        textStyle: {
          color: '#F4F7FC',
          ...(options.tooltip.textStyle || {}),
        },
      }
    : options.tooltip,
  dataZoom: Array.isArray(options.dataZoom)
    ? options.dataZoom.map((zoom: any) => ({
        ...zoom,
        textStyle: {
          color: textColor,
          ...(zoom?.textStyle || {}),
        },
        borderColor: zoom?.borderColor || axisColor,
        fillerColor: zoom?.fillerColor || 'rgba(82, 160, 255, 0.24)',
        handleStyle: {
          ...(zoom?.handleStyle || {}),
          color: zoom?.handleStyle?.color || '#D9E3F0',
          borderColor: zoom?.handleStyle?.borderColor || '#D9E3F0',
        },
      }))
    : options.dataZoom,
})

export default withDarkEchartsTheme
