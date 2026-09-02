<script setup lang="ts">
/** 后台监控面板共用的 ECharts 宿主：负责初始化、数据更新与容器尺寸自适应。 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent, AriaComponent } from 'echarts/components'
import { init, use, type ECharts, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'

use([BarChart, LineChart, PieChart, GridComponent, LegendComponent, TooltipComponent, AriaComponent, CanvasRenderer])

const props = defineProps<{ option: EChartsCoreOption; height?: string }>()
const host = ref<HTMLDivElement>()
let instance: ECharts | undefined
let observer: ResizeObserver | undefined

onMounted(() => {
  if (!host.value) return
  instance = init(host.value)
  instance.setOption(props.option)
  observer = new ResizeObserver(() => instance?.resize())
  observer.observe(host.value)
})

watch(() => props.option, option => instance?.setOption(option, { notMerge: true }), { deep: true })

onBeforeUnmount(() => {
  observer?.disconnect()
  instance?.dispose()
  instance = undefined
})
</script>

<template>
  <div ref="host" class="q-chart" :style="{ height: height || '260px' }" />
</template>
