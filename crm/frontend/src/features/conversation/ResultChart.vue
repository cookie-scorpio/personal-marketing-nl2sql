<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import type { ChartSpec } from '../../app/types'

use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, CanvasRenderer])

const props = defineProps<{ chart: ChartSpec; rows: Record<string, unknown>[] }>()
const host = ref<HTMLDivElement>()
let instance: ECharts | undefined
let observer: ResizeObserver | undefined

/** 后端只描述数据角色，页面在这里统一颜色、坐标轴和提示框，避免各查询产生不一致的视觉样式。 */
function buildOption(): EChartsCoreOption {
  const dimension = props.chart.dimension_key
  const palette = ['#b4232d', '#b28a45', '#39715a', '#466a8d']
  if (props.chart.type === 'PIE') {
    const series = props.chart.series[0]
    return {
      color: palette,
      tooltip: { trigger: 'item' },
      legend: { bottom: 0, type: 'scroll', textStyle: { color: '#69615d', fontSize: 11 } },
      series: [{
        name: series.label,
        type: 'pie',
        radius: ['42%', '68%'],
        center: ['50%', '43%'],
        avoidLabelOverlap: true,
        label: { color: '#5f5753', formatter: '{b}\n{d}%' },
        data: props.rows.map(row => ({ name: String(row[dimension] ?? '未分类'), value: Number(row[series.key] ?? 0) })),
      }],
    }
  }
  return {
    color: palette,
    tooltip: { trigger: 'axis' },
    legend: { top: 0, right: 4, textStyle: { color: '#69615d', fontSize: 11 } },
    grid: { left: 48, right: 18, top: 42, bottom: 48, containLabel: false },
    xAxis: {
      type: 'category',
      data: props.rows.map(row => String(row[dimension] ?? '未分类')),
      axisLabel: { color: '#756d69', rotate: props.rows.length > 8 ? 28 : 0, overflow: 'truncate', width: 90 },
      axisLine: { lineStyle: { color: '#ddd6d1' } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value', axisLabel: { color: '#817974' }, splitLine: { lineStyle: { color: '#eee9e5', type: 'dashed' } },
    },
    series: props.chart.series.map((series, index) => ({
      name: series.label,
      type: props.chart.type === 'LINE' ? 'line' : 'bar',
      smooth: props.chart.type === 'LINE',
      symbolSize: 7,
      barMaxWidth: 34,
      itemStyle: props.chart.type === 'BAR' ? { borderRadius: [4, 4, 0, 0] } : undefined,
      lineStyle: props.chart.type === 'LINE' ? { width: 3 } : undefined,
      data: props.rows.map(row => Number(row[series.key] ?? 0)),
      z: props.chart.series.length - index,
    })),
  }
}

async function render() {
  await nextTick()
  if (!host.value) return
  instance ||= init(host.value, undefined, { renderer: 'canvas' })
  instance.setOption(buildOption(), true)
}

onMounted(() => {
  render()
  if (host.value) {
    observer = new ResizeObserver(() => instance?.resize())
    observer.observe(host.value)
  }
})
watch(() => [props.chart, props.rows], render, { deep: true })
onBeforeUnmount(() => { observer?.disconnect(); instance?.dispose() })
</script>

<template>
  <div ref="host" class="result-chart" role="img" :aria-label="chart.title" />
</template>
