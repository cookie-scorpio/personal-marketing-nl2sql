<script setup lang="ts">
/** 根据服务端给出的字段角色绘制图表，并同步处理容器尺寸、无障碍和减少动效偏好。 */
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart, PieChart, ScatterChart, HeatmapChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, VisualMapComponent, DataZoomComponent, AriaComponent } from 'echarts/components'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import type { ChartSpec } from '../../app/types'

use([BarChart, LineChart, PieChart, ScatterChart, HeatmapChart, GridComponent, LegendComponent,
  TooltipComponent, VisualMapComponent, DataZoomComponent, AriaComponent, CanvasRenderer])
const props = defineProps<{ chart: ChartSpec; rows: Record<string, unknown>[] }>()
const host = ref<HTMLDivElement>()
let instance: ECharts | undefined
let observer: ResizeObserver | undefined
const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
const palette = ['#b4232d', '#466a8d', '#39715a', '#b28a45']
const width = ref(0)
const pieItems = computed(() => {
  const measure = props.chart.series[0]
  if (props.chart.type !== 'PIE' || !measure) return []
  // 负值不参与构成，合计非正时没有可表达的构成关系，返回空列表避免错误的0.00%展示。
  const rows = props.rows.filter(row => number(row[measure.key]) !== null && number(row[measure.key])! >= 0)
  const sum = rows.reduce((total, row) => total + number(row[measure.key])!, 0)
  if (sum <= 0) return []
  return rows.map((row, index) => ({ name: label(row[props.chart.dimension_key]), value: number(row[measure.key])!,
    percent: ((number(row[measure.key])! / sum) * 100).toFixed(2), color: palette[index % palette.length] }))
})
// 三栏结果卡片通常不足以安全容纳饼图外标签；窄图使用完整HTML图例承载文字与数值。
const compactPie = computed(() => width.value < 680 || pieItems.value.length > 6)
function number(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null
  const parsed = Number(value)
  return Number.isFinite(parsed) ? parsed : null
}
const label = (value: unknown) => value === null || value === undefined ? '未分类' : String(value)
const format = (value: number) => new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2, notation: Math.abs(value) >= 100000 ? 'compact' : 'standard' }).format(value)

/** 后端提供字段角色，前端只负责展示，不补算或把空值替换成零。 */
function buildOption(): EChartsCoreOption {
  const chart = props.chart
  const dimension = chart.dimension_key
  const measure = chart.series[0]
  const base = { color: palette, animation: !motion.matches, aria: { enabled: true },
    tooltip: { trigger: 'axis', confine: true },
    legend: { type: 'scroll', top: 0, textStyle: { color: '#69615d', fontSize: 11 } } }
  if (!measure) return base
  if (chart.type === 'PIE') return {
    ...base, tooltip: { trigger: 'item', confine: true, renderMode: 'richText' }, legend: { show: false },
    series: [{ name: measure.label, type: 'pie', radius: compactPie.value ? ['36%', '66%'] : ['30%', '50%'], center: ['50%', '50%'],
      avoidLabelOverlap: true, labelLayout: { hideOverlap: true },
      label: { show: !compactPie.value, formatter: '{b}\n{d}%', alignTo: 'edge', edgeDistance: 10,
        bleedMargin: 4, overflow: 'break', width: Math.max(72, Math.min(150, width.value * .2)), fontSize: 11 },
      labelLine: { show: !compactPie.value, length: 12, length2: 8 },
      data: props.rows.filter(row => number(row[measure.key]) !== null)
        .map(row => ({ name: label(row[dimension]), value: number(row[measure.key]) })) }],
  }
  if (chart.type === 'SCATTER') return {
    ...base, tooltip: { trigger: 'item', confine: true },
    grid: { left: 20, right: 35, top: 65, bottom: 40, containLabel: true },
    xAxis: { type: 'value', name: chart.dimension_label || dimension, nameLocation: 'middle', nameGap: 28, axisLabel: { formatter: format } },
    yAxis: { type: 'value', name: measure.unit || measure.label, axisLabel: { formatter: format } },
    series: [{ name: measure.label, type: 'scatter', symbolSize: 10,
      data: props.rows.filter(row => number(row[dimension]) !== null && number(row[measure.key]) !== null)
        .map(row => [number(row[dimension]), number(row[measure.key])]) }],
  }
  if (chart.type === 'HEATMAP' && chart.secondary_dimension_key) {
    const second = chart.secondary_dimension_key
    const xs = [...new Set(props.rows.map(row => label(row[dimension])))]
    const ys = [...new Set(props.rows.map(row => label(row[second])))]
    const data = props.rows.filter(row => number(row[measure.key]) !== null).map(row =>
      [xs.indexOf(label(row[dimension])), ys.indexOf(label(row[second])), number(row[measure.key]) as number])
    const values = data.map(item => item[2]!)
    // 无有效数值或全部相等时，visualMap 的min/max会是±Infinity或相等，给出安全缺省。
    const vmin = values.length ? Math.min(...values) : 0
    const vmax = values.length ? Math.max(...values) : 1
    return { ...base, tooltip: { trigger: 'item', confine: true },
      grid: { left: 15, right: 25, top: 45, bottom: 65, containLabel: true },
      xAxis: { type: 'category', data: xs, axisLabel: { rotate: xs.length > 7 ? 30 : 0 } },
      yAxis: { type: 'category', data: ys },
      visualMap: { min: vmin, max: vmax > vmin ? vmax : vmin + 1, calculable: true, orient: 'horizontal',
        left: 'center', bottom: 0, inRange: { color: ['#f8ecec', '#d77a82', '#9e1724'] } },
      series: [{ name: measure.label, type: 'heatmap', data, label: { show: data.length <= 30 } }],
    }
  }
  const time = chart.type === 'LINE' || chart.type === 'AREA'
  if (time && chart.secondary_dimension_key) {
    const category = chart.secondary_dimension_key
    const dates = [...new Set(props.rows.map(row => label(row[dimension])))].sort((a, b) => a.localeCompare(b, 'zh-CN', { numeric: true }))
    const groups = [...new Set(props.rows.map(row => label(row[category])))]
    return { ...base, grid: { left: 15, right: 25, top: 65, bottom: 40, containLabel: true },
      xAxis: { type: 'category', data: dates }, yAxis: { type: 'value', name: measure.unit || measure.label },
      series: groups.map(group => ({ name: group, type: 'line', connectNulls: false,
        data: dates.map(date => { const row = props.rows.find(item => label(item[dimension]) === date && label(item[category]) === group); return row ? number(row[measure.key]) : null }) })) }
  }
  const rows = time ? [...props.rows].sort((a, b) => label(a[dimension]).localeCompare(label(b[dimension]), 'zh-CN', { numeric: true })) : props.rows
  const horizontal = !time && (rows.length > 8 || rows.some(row => label(row[dimension]).length > 10))
  const category = { type: 'category', data: rows.map(row => label(row[dimension])),
    axisLabel: { color: '#756d69', overflow: 'truncate', width: horizontal ? 110 : 90, rotate: !horizontal && rows.length > 8 ? 28 : 0 },
    axisTick: { show: false }, axisLine: { lineStyle: { color: '#ddd6d1' } } }
  const value = { type: 'value', name: measure.unit || measure.label, axisLabel: { color: '#817974', formatter: format },
    splitLine: { lineStyle: { color: '#eee9e5', type: 'dashed' } } }
  return { ...base,
    tooltip: { trigger: 'axis', confine: true, valueFormatter: (v: unknown) => number(v) === null ? '无数据' : String(v) + (measure.unit || '') },
    grid: { left: 12, right: 28, top: 60, bottom: rows.length > 20 ? 60 : 38, containLabel: true },
    xAxis: horizontal ? value : category, yAxis: horizontal ? { ...category, inverse: true } : value,
    dataZoom: rows.length > 20 ? [{ type: 'slider', ...(horizontal ? { yAxisIndex: 0, right: 0 } : { xAxisIndex: 0, bottom: 0 }), start: 0, end: Math.min(100, 2000 / rows.length) }] : [],
    series: chart.series.map(series => ({
      name: series.label, type: time ? 'line' : 'bar', smooth: false, connectNulls: false,
      symbolSize: 6, barMaxWidth: 34, areaStyle: chart.type === 'AREA' ? { opacity: 0.13 } : undefined,
      data: rows.map(row => number(row[series.key])),
    })),
  }
}
async function render() {
  await nextTick()
  if (!host.value) return
  width.value = host.value.clientWidth
  instance ||= init(host.value, undefined, { renderer: 'canvas' })
  instance.resize()
  instance.setOption(buildOption(), true)
}
onMounted(() => {
  render()
  motion.addEventListener('change', render)
  if (host.value) { observer = new ResizeObserver(() => { void render() }); observer.observe(host.value) }
})
watch(() => [props.chart, props.rows], render, { deep: true })
onBeforeUnmount(() => { motion.removeEventListener('change', render); observer?.disconnect(); instance?.dispose() })
</script>

<template>
  <div class="responsive-chart">
  <div ref="host" class="result-chart" role="img" :aria-label="chart.title + '。可切换到数据明细查看完整数值。'" />
  <ul v-if="chart.type === 'PIE'" class="pie-readable-legend" aria-label="饼图完整分类与占比">
    <li v-for="(item, index) in pieItems" :key="`${item.name}-${index}`"><i :style="{ backgroundColor: item.color }" /><span :title="item.name">{{ item.name }}</span><strong>{{ item.value.toLocaleString('zh-CN') }}{{ chart.series[0]?.unit || '' }} · {{ item.percent }}%</strong></li>
  </ul>
  </div>
</template>
