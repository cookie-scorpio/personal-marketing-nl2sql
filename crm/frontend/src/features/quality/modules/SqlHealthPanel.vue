<script setup lang="ts">
/** SQL 健康度页：生成/校验/执行阶段事实、修复轨迹、模型调用与失败分类的窗口统计。 */
import { computed, ref, watch } from 'vue'
import QChart from '../components/QChart.vue'
import { fetchSqlHealth } from '../api'
import { usePolling } from '../usePolling'
import type { GroupCount } from '../types'

const REFRESH_MS = 30000
const windowHours = ref(24)
// loader 每次轮询时读取当前窗口，窗口切换后 watch 立即补一次拉取。
const { data, error, refresh } = usePolling(() => fetchSqlHealth(windowHours.value), REFRESH_MS)
watch(windowHours, () => void refresh())

const PHASE_LABELS: Record<string, string> = {
  GENERATED: 'SQL 生成', REJECTED: '校验拒绝', EXECUTING: '开始执行', EXECUTED: '执行成功',
  SQL_ERROR: '执行报错', RESULT_ALIGNED: '结果复核通过', RESULT_MISMATCH: '结果不一致',
  TIMED_OUT: '执行超时', CANCELLED: '已取消', UNKNOWN: '未知阶段',
}
const REPAIR_LABELS: Record<string, string> = {
  STARTED: '修复启动', GENERATED: '产出候选', APPLIED: '修复生效', REJECTED: '候选被拒', MODEL_FAILED: '模型失败',
}
const EVENT_LABELS: Record<string, string> = {
  QUERY_SUCCESS: '问数成功', QUERY_FAILED: '问数失败', QUERY_TIMED_OUT: '问数超时', QUERY_DEGRADED: '降级完成',
  QUERY_SQL_ERROR: 'SQL 报错', QUERY_RESULT_MISMATCH: '结果不一致', QUERY_FALLBACK: '模板兜底', QUERY_CANCELLED: '用户取消',
  MODEL_CALL_COMPLETED: '模型调用完成', MODEL_CALL_FAILED: '模型调用失败', MODEL_RESPONSE_REJECTED: '模型响应被拒',
  SQL_ATTEMPT_RECORDED: 'SQL 尝试记录', REPAIR_STARTED: '修复启动', REPAIR_APPLIED: '修复生效',
}
const phaseRows = computed(() => withLabels(data.value?.phase_counts, PHASE_LABELS))
const repairRows = computed(() => withLabels(data.value?.repair_counts, REPAIR_LABELS))
const eventRows = computed(() => withLabels(data.value?.event_type_counts, EVENT_LABELS))
const trend = computed(() => data.value?.hourly_trend ?? [])
const modelCalls = computed(() => data.value?.model_calls)

const trendOption = computed(() => ({
  tooltip: { trigger: 'axis', confine: true },
  grid: { left: 16, right: 16, top: 20, bottom: 8, containLabel: true },
  xAxis: { type: 'category', data: trend.value.map(item => item.bucket.slice(5, 13)) },
  yAxis: { type: 'value', minInterval: 1 },
  series: [{ name: 'SQL 尝试', type: 'bar', data: trend.value.map(item => item.total_count), itemStyle: { color: '#466a8d' }, barMaxWidth: 22 }],
}))

function withLabels(rows: GroupCount[] | undefined, labels: Record<string, string>) {
  return (rows ?? []).map(row => ({ ...row, label: labels[row.group_key] ?? row.group_key }))
}
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`SQL 健康数据加载失败：${error}`" :closable="false" show-icon />
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
          <span>SQL 尝试</span>
          <strong>{{ (data.phase_counts ?? []).reduce((sum, row) => sum + row.group_count, 0).toLocaleString() }}</strong>
          <small>窗口内全部 SQL 事实</small>
        </div>
        <div class="metric-card" :class="modelCalls && modelCalls.failed_calls > 0 ? 'is-warn' : 'is-ok'">
          <span>模型调用</span><strong>{{ modelCalls?.total_calls ?? 0 }}</strong>
          <small>失败 {{ modelCalls?.failed_calls ?? 0 }} 次 · 均耗时 {{ Math.round(modelCalls?.avg_elapsed_ms ?? 0) }} ms</small>
        </div>
        <div class="metric-card">
          <span>修复触发</span>
          <strong>{{ (repairRows.find(row => row.group_key === 'STARTED')?.group_count ?? 0).toLocaleString() }}</strong>
          <small>生效 {{ repairRows.find(row => row.group_key === 'APPLIED')?.group_count ?? 0 }} 次</small>
        </div>
        <div class="metric-card">
          <span>结果复核</span>
          <strong>{{ (phaseRows.find(row => row.group_key === 'RESULT_ALIGNED')?.group_count ?? 0).toLocaleString() }}</strong>
          <small>不一致 {{ phaseRows.find(row => row.group_key === 'RESULT_MISMATCH')?.group_count ?? 0 }} 次</small>
        </div>
      </div>

      <div class="panel-grid">
        <section class="panel-card">
          <h3>SQL 尝试趋势</h3>
          <QChart v-if="trend.length" :option="trendOption" height="230px" />
          <el-empty v-else description="窗口内暂无 SQL 尝试" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>尝试阶段分布</h3>
          <el-table v-if="phaseRows.length" :data="phaseRows" size="small" height="230">
            <el-table-column label="阶段" prop="label" />
            <el-table-column label="次数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="窗口内暂无数据" :image-size="72" />
        </section>
      </div>

      <div class="panel-grid">
        <section class="panel-card">
          <h3>修复轨迹</h3>
          <el-table v-if="repairRows.length" :data="repairRows" size="small">
            <el-table-column label="状态" prop="label" />
            <el-table-column label="次数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="窗口内没有触发修复" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>质量事实分类</h3>
          <el-table v-if="eventRows.length" :data="eventRows" size="small" height="260">
            <el-table-column label="事实类型" prop="label" />
            <el-table-column label="次数" prop="group_count" width="110" align="right" />
          </el-table>
          <el-empty v-else description="窗口内没有质量事实" :image-size="72" />
        </section>
      </div>
    </template>
  </div>
</template>
