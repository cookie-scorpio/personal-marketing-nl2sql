<script setup lang="ts">
/** 后台总览：健康、资源、业务规模、执行量与待回流候选的核心指标聚合。 */
import { computed } from 'vue'
import { ArrowRight } from '@element-plus/icons-vue'
import QChart from '../components/QChart.vue'
import { fetchOverview } from '../api'
import { usePolling } from '../usePolling'
import { eventLabel } from '../eventLabels'
import type { QualityModule } from '../types'

defineEmits<{ navigate: [module: QualityModule] }>()

const REFRESH_MS = 15000
const { data, error } = usePolling(() => fetchOverview(), REFRESH_MS)

const statusLabel = computed(() => {
  if (!data.value) return '检测中'
  return data.value.health_status === 'UP' ? '运行正常' : '部分降级'
})
const uptimeLabel = computed(() => {
  const seconds = data.value?.uptime_seconds ?? 0
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  return days > 0 ? `${days} 天 ${hours} 小时` : hours > 0 ? `${hours} 小时 ${minutes} 分` : `${minutes} 分钟`
})
const trend = computed(() => data.value?.business.trend ?? [])
const statusRows = computed(() => data.value?.business.status_counts ?? [])
const eventRows = computed(() => data.value?.business.event_counts ?? [])
const candidateRows = computed(() => data.value?.candidate_counts_24h ?? [])
const successRate = computed(() => {
  const rows = statusRows.value
  const total = rows.reduce((sum, row) => sum + row.group_count, 0)
  if (!total) return null
  const success = rows.filter(row => row.group_key === 'SUCCESS' || row.group_key === 'DEGRADED')
    .reduce((sum, row) => sum + row.group_count, 0)
  return `${((success / total) * 100).toFixed(1)}%`
})

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis', confine: true },
  legend: { top: 0, textStyle: { color: '#69615d', fontSize: 11 } },
  grid: { left: 16, right: 16, top: 36, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: trend.value.map(item => item.bucket.slice(5, 13)) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: '执行量', type: 'bar', data: trend.value.map(item => item.total_count), itemStyle: { color: '#466a8d' }, barMaxWidth: 22 },
    { name: '成功', type: 'line', data: trend.value.map(item => item.success_count), itemStyle: { color: '#39715a' }, smooth: true },
    { name: '失败', type: 'line', data: trend.value.map(item => item.failure_count), itemStyle: { color: '#b4232d' }, smooth: true },
  ],
}))

const STATUS_LABELS: Record<string, string> = {
  SUCCESS: '成功', DEGRADED: '降级完成', FAILED: '失败', TIMED_OUT: '超时', CANCELLED: '已取消',
  RECEIVED: '已接收', INTENT_ANALYZING: '意图识别', ASKING: '等待澄清', SQL_GENERATING: '生成SQL',
  VALIDATING: '校验中', CONFIRMING: '待确认', EXECUTING: '执行中', REPAIRING: '修复中', FALLING_BACK: '模板兜底',
}
const statusName = (key: string) => STATUS_LABELS[key] ?? key
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`总览数据加载失败：${error}`" :closable="false" show-icon />
    <template v-if="data">
      <div class="metric-grid">
        <div class="metric-card" :class="data.health_status === 'UP' ? 'is-ok' : 'is-warn'">
          <span>服务健康</span><strong>{{ statusLabel }}</strong><small>已运行 {{ uptimeLabel }}</small>
        </div>
        <div class="metric-card">
          <span>进程 CPU</span><strong>{{ (data.process_cpu_load * 100).toFixed(1) }}%</strong>
          <small>JVM 堆 {{ data.jvm_heap_used_mb }} / {{ data.jvm_heap_max_mb }} MB</small>
        </div>
        <div class="metric-card">
          <span>在册客户</span><strong>{{ data.business.customers.toLocaleString() }}</strong>
          <small>客户主档数据快照（含在册与 VIP）</small>
        </div>
        <div class="metric-card">
          <span>24 小时执行</span>
          <strong>{{ trend.reduce((sum, item) => sum + item.total_count, 0).toLocaleString() }}</strong>
          <small>成功率 {{ successRate ?? '暂无数据' }}</small>
        </div>
        <div class="metric-card">
          <span>活跃会话</span><strong>{{ data.active_sessions.toLocaleString() }}</strong><small>未删除的会话</small>
        </div>
        <button type="button" class="metric-card metric-card-link" :class="{ 'is-warn': data.pending_candidates > 0 }"
          @click="$emit('navigate', 'feedback')">
          <span>待回流候选</span><strong>{{ data.pending_candidates.toLocaleString() }}</strong>
          <small>进入数据回流审核 <el-icon><ArrowRight /></el-icon></small>
        </button>
      </div>

      <div class="panel-grid">
        <section class="panel-card">
          <h3>24 小时执行趋势</h3>
          <QChart v-if="trend.length" :option="trendOption" height="240px" />
          <el-empty v-else description="窗口内暂无执行记录" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>任务状态分布（24 小时）</h3>
          <el-table v-if="statusRows.length" :data="statusRows" size="small" height="240">
            <el-table-column label="状态" prop="group_key">
              <template #default="{ row }">{{ statusName(row.group_key) }}</template>
            </el-table-column>
            <el-table-column label="数量" prop="group_count" width="100" align="right" />
          </el-table>
          <el-empty v-else description="窗口内暂无任务" :image-size="72" />
        </section>
      </div>

      <div class="panel-grid">
        <section class="panel-card">
          <h3>质量事实分类（24 小时）</h3>
          <el-table v-if="eventRows.length" :data="eventRows" size="small" height="260">
            <el-table-column label="事实类型" prop="label">
              <template #default="{ row }">{{ eventLabel(row.group_key) }}</template>
            </el-table-column>
            <el-table-column label="次数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="窗口内没有质量事实" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>待回流候选构成（24 小时）</h3>
          <el-table v-if="candidateRows.length" :data="candidateRows" size="small" height="260">
            <el-table-column label="候选类型" prop="label">
              <template #default="{ row }">{{ eventLabel(row.group_key) }}</template>
            </el-table-column>
            <el-table-column label="次数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="窗口内没有回流候选" :image-size="72" />
        </section>
      </div>
    </template>
  </div>
</template>
