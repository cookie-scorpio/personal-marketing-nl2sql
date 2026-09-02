<script setup lang="ts">
/** 系统健康页：各组件健康结论、运行环境与线程池水位的实时展示。 */
import { computed } from 'vue'
import { fetchHealth } from '../api'
import { usePolling } from '../usePolling'

const REFRESH_MS = 10000
const { data, error } = usePolling(() => fetchHealth(), REFRESH_MS)

const uptimeLabel = computed(() => {
  const seconds = data.value?.uptime_seconds ?? 0
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return days > 0 ? `${days} 天 ${hours} 小时 ${minutes} 分` : `${hours} 小时 ${minutes} 分钟`
})

const EXECUTOR_LABELS: Record<string, string> = {
  queryExecutor: '问数执行线程池',
  qualityEventExecutor: '质量事实线程池',
}
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`健康数据加载失败：${error}`" :closable="false" show-icon />
    <template v-if="data">
      <div class="metric-grid">
        <div class="metric-card" :class="data.status === 'UP' ? 'is-ok' : 'is-warn'">
          <span>整体状态</span><strong>{{ data.status === 'UP' ? 'UP' : 'DEGRADED' }}</strong>
          <small>已运行 {{ uptimeLabel }}</small>
        </div>
        <div class="metric-card">
          <span>运行环境</span><strong>Java {{ data.java_version }}</strong>
          <small>{{ data.available_processors }} 核 · {{ data.os_name }}</small>
        </div>
        <div class="metric-card">
          <span>JVM 堆内存</span>
          <strong>{{ data.jvm_heap_used_mb }} / {{ data.jvm_heap_max_mb }} MB</strong>
          <small>{{ data.jvm_heap_max_mb ? ((data.jvm_heap_used_mb / data.jvm_heap_max_mb) * 100).toFixed(1) : '0' }}% 已用</small>
        </div>
      </div>

      <section class="panel-card">
        <h3>组件健康</h3>
        <div class="health-list">
          <article v-for="component in data.components" :key="component.key" class="health-item" :class="component.healthy ? 'is-ok' : 'is-bad'">
            <header>
              <i class="health-dot" />
              <strong>{{ component.label }}</strong>
              <el-tag :type="component.healthy ? 'success' : 'danger'" size="small" effect="light">
                {{ component.healthy ? '正常' : '异常' }}
              </el-tag>
            </header>
            <p>{{ component.detail }}</p>
            <ul v-if="component.adapters" class="health-extra">
              <li v-for="adapter in component.adapters" :key="adapter.provider">
                适配器 {{ adapter.provider }}：
                <el-tag :type="adapter.available ? 'success' : 'info'" size="small">{{ adapter.available ? '可用' : '不可用' }}</el-tag>
              </li>
            </ul>
            <ul v-if="component.query_executor || component.quality_executor" class="health-extra">
              <li v-for="pool in [component.query_executor, component.quality_executor].filter(Boolean)" :key="pool!.name">
                {{ EXECUTOR_LABELS[pool!.name] ?? pool!.name }}：
                活跃 {{ pool!.active_count ?? '-' }} / 线程 {{ pool!.pool_size ?? '-' }} / 队列 {{ pool!.queue_size ?? '-' }}
              </li>
            </ul>
          </article>
        </div>
      </section>
    </template>
  </div>
</template>
