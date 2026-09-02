<script setup lang="ts">
/** 资源监控页：CPU、内存、JVM 堆的实时曲线与 GPU 独立探测结果。 */
import { computed } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import QChart from '../components/QChart.vue'
import { fetchResources } from '../api'
import { usePolling } from '../usePolling'

const REFRESH_MS = 5000
const { data, error, loading, refresh } = usePolling(() => fetchResources(), REFRESH_MS)

const history = computed(() => data.value?.history ?? [])
const hasGpu = computed(() => (data.value?.gpu.length ?? 0) > 0)
const memoryPercent = computed(() => {
  if (!data.value?.os_total_memory_mb) return 0
  return ((data.value.os_used_memory_mb / data.value.os_total_memory_mb) * 100).toFixed(1)
})
const heapPercent = computed(() => {
  const used = data.value?.jvm_heap_used_mb ?? 0
  const max = data.value?.jvm_heap_max_mb ?? 0
  return max ? ((used / max) * 100).toFixed(1) : '0'
})
const percent = (value: number | undefined) => `${((value ?? 0) * 100).toFixed(1)}%`

const resourceOption = computed(() => ({
  tooltip: { trigger: 'axis', confine: true, valueFormatter: (value: number) => value },
  legend: { top: 0, textStyle: { color: '#69615d', fontSize: 11 } },
  grid: { left: 16, right: 16, top: 36, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: history.value.map(item => item.sampled_at.slice(11, 19)) },
  yAxis: [
    { type: 'value', name: '%', max: 100, position: 'left' },
    { type: 'value', name: 'MB', position: 'right' },
  ],
  series: [
    { name: '进程 CPU %', type: 'line', showSymbol: false, data: history.value.map(item => +(item.process_cpu_load * 100).toFixed(2)), itemStyle: { color: '#b4232d' } },
    { name: '系统 CPU %', type: 'line', showSymbol: false, data: history.value.map(item => +(item.system_cpu_load * 100).toFixed(2)), itemStyle: { color: '#b28a45' } },
    { name: 'GPU %', type: 'line', showSymbol: false, yAxisIndex: 0, data: history.value.map(item => item.gpu_utilization_percent ?? null), itemStyle: { color: '#39715a' } },
    { name: 'JVM 堆 MB', type: 'line', showSymbol: false, yAxisIndex: 1, data: history.value.map(item => item.jvm_heap_used_mb), itemStyle: { color: '#466a8d' } },
  ],
}))
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`资源数据加载失败：${error}`" :closable="false" show-icon />
    <template v-if="data">
      <div class="metric-grid">
        <div class="metric-card">
          <span>进程 CPU</span><strong>{{ percent(data.process_cpu_load) }}</strong><small>每 5 秒采样</small>
        </div>
        <div class="metric-card">
          <span>系统 CPU</span><strong>{{ percent(data.system_cpu_load) }}</strong><small>{{ data.thread_count }} 线程（峰值 {{ data.peak_thread_count }}）</small>
        </div>
        <div class="metric-card">
          <span>物理内存</span><strong>{{ memoryPercent }}%</strong><small>{{ data.os_used_memory_mb }} / {{ data.os_total_memory_mb }} MB</small>
        </div>
        <div class="metric-card">
          <span>JVM 堆</span><strong>{{ heapPercent }}%</strong><small>{{ data.jvm_heap_used_mb }} / {{ data.jvm_heap_max_mb }} MB</small>
        </div>
      </div>

      <section class="panel-card">
        <header class="panel-card-head">
          <h3>资源趋势（近 {{ history.length * 5 >= 60 ? Math.round(history.length * 5 / 60) : 1 }} 分钟）</h3>
          <el-button size="small" :icon="Refresh" :loading="loading" @click="() => refresh()">立即刷新</el-button>
        </header>
        <QChart :option="resourceOption" height="280px" />
      </section>

      <section class="panel-card">
        <h3>GPU</h3>
        <div v-if="hasGpu" class="gpu-grid">
          <article v-for="gpu in data.gpu" :key="gpu.index" class="gpu-card">
            <header><strong>GPU {{ gpu.index }}</strong><span>{{ gpu.name }}</span></header>
            <el-progress :percentage="Number(gpu.utilization_percent ?? 0)" :stroke-width="12" />
            <small>显存 {{ gpu.memory_used_mb ?? '-' }} / {{ gpu.memory_total_mb ?? '-' }} MB · {{ gpu.temperature_celsius ?? '-' }} ℃</small>
          </article>
        </div>
        <el-empty v-else description="未检测到 GPU；本部署未安装 nvidia-smi 或无独立显卡" :image-size="72" />
      </section>
    </template>
  </div>
</template>
