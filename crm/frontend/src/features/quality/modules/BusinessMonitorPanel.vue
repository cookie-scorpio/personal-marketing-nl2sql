<script setup lang="ts">
/** 业务监控页：在册客户规模、问数执行量、成功率与耗时分布的窗口统计。 */
import { computed, ref, watch } from 'vue'
import QChart from '../components/QChart.vue'
import { fetchBusiness } from '../api'
import { usePolling } from '../usePolling'

const REFRESH_MS = 30000
const windowHours = ref(24)
const { data, error, refresh } = usePolling(() => fetchBusiness(windowHours.value), REFRESH_MS)
watch(windowHours, () => void refresh())

const trend = computed(() => data.value?.hourly_trend ?? [])
const statusRows = computed(() => data.value?.status_counts ?? [])
const LEVEL_LABELS: Record<string, string> = {
  NORMAL: '普通客户', GOLD: '金卡客户', PLATINUM: '铂金客户', DIAMOND: '钻石客户',
  A: 'A级客户', B: 'B级客户', C: 'C级客户', D: 'D级客户', UNKNOWN: '未分级',
}
const levelRows = computed(() => (data.value?.customers.by_level ?? [])
  .map(row => ({ ...row, label: LEVEL_LABELS[row.group_key] ?? row.group_key })))
const REGION_LABELS: Record<string, string> = {
  EAST: '东部', SOUTH: '南部', WEST: '西部', NORTH: '北部', CENTER: '中部',
}
const regionRows = computed(() => (data.value?.customers.top_regions ?? [])
  .map(row => ({ ...row, label: REGION_LABELS[row.group_key] ?? row.group_key })))
const totalExecutions = computed(() => statusRows.value.reduce((sum, row) => sum + row.group_count, 0))
const successRate = computed(() => {
  if (!totalExecutions.value) return null
  const success = statusRows.value
    .filter(row => row.group_key === 'SUCCESS' || row.group_key === 'DEGRADED')
    .reduce((sum, row) => sum + row.group_count, 0)
  return `${((success / totalExecutions.value) * 100).toFixed(1)}%`
})

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis', confine: true },
  legend: { top: 0, textStyle: { color: '#69615d', fontSize: 11 } },
  grid: { left: 16, right: 16, top: 36, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: trend.value.map(item => item.bucket.slice(5, 13)) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [
    { name: '执行量', type: 'bar', stack: 'total', data: trend.value.map(item => item.success_count), itemStyle: { color: '#39715a' }, barMaxWidth: 22 },
    { name: '失败与超时', type: 'bar', stack: 'total', data: trend.value.map(item => item.failure_count), itemStyle: { color: '#b4232d' }, barMaxWidth: 22 },
    { name: '总量', type: 'line', data: trend.value.map(item => item.total_count), itemStyle: { color: '#466a8d' }, smooth: true },
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
    <el-alert v-if="error" type="error" :title="`业务数据加载失败：${error}`" :closable="false" show-icon />
    <div class="quality-toolbar">
      <el-radio-group v-model="windowHours" size="small">
        <el-radio-button :value="6">近 6 小时</el-radio-button>
        <el-radio-button :value="24">近 24 小时</el-radio-button>
        <el-radio-button :value="72">近 3 天</el-radio-button>
        <el-radio-button :value="168">近 7 天</el-radio-button>
      </el-radio-group>
    </div>
    <template v-if="data">
      <div class="metric-grid">
        <div class="metric-card">
          <span>在册客户</span><strong>{{ data.customers.total_customers.toLocaleString() }}</strong>
          <small>在册 {{ data.customers.active_customers.toLocaleString() }} · VIP {{ data.customers.vip_customers.toLocaleString() }}</small>
        </div>
        <div class="metric-card">
          <span>窗口执行量</span><strong>{{ totalExecutions.toLocaleString() }}</strong>
          <small>窗口内提交的问数任务条数（含成功、失败、超时与取消） · 成功率 {{ successRate ?? '暂无数据' }}</small>
        </div>
        <div class="metric-card">
          <span>平均耗时</span><strong>{{ data.duration.avg_seconds }}s</strong>
          <small>P50 {{ data.duration.p50_seconds }}s · P95 {{ data.duration.p95_seconds }}s</small>
        </div>
        <div class="metric-card">
          <span>活跃会话</span><strong>{{ data.active_sessions.toLocaleString() }}</strong>
          <small>采样 {{ data.duration.samples }} 条终态任务</small>
        </div>
      </div>

      <section class="panel-card">
        <h3>执行趋势</h3>
        <QChart v-if="trend.length" :option="trendOption" height="260px" />
        <el-empty v-else description="窗口内暂无执行记录" :image-size="72" />
      </section>

      <div class="panel-grid panel-grid-three">
        <section class="panel-card">
          <h3>客户等级分布</h3>
          <el-table v-if="levelRows.length" :data="levelRows" size="small">
            <el-table-column label="等级" prop="label" />
            <el-table-column label="客户数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="暂无客户数据" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>区域客户 TOP 10</h3>
          <el-table v-if="regionRows.length" :data="regionRows" size="small" height="260">
            <el-table-column label="区域" prop="label" />
            <el-table-column label="客户数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="暂无区域数据" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>任务状态分布</h3>
          <el-table v-if="statusRows.length" :data="statusRows" size="small" height="260">
            <el-table-column label="状态" prop="group_key">
              <template #default="{ row }">{{ statusName(row.group_key) }}</template>
            </el-table-column>
            <el-table-column label="数量" prop="group_count" width="90" align="right" />
          </el-table>
          <el-empty v-else description="窗口内暂无任务" :image-size="72" />
        </section>
      </div>
    </template>
  </div>
</template>
