<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { QueryResult } from '../../app/types'
import ResultChart from './ResultChart.vue'
defineProps<{ result: QueryResult }>()
const tab = ref('analysis')
const labels: Record<string, string> = { BAR: '柱状图', LINE: '折线图', AREA: '面积图', PIE: '构成图', SCATTER: '散点图', HEATMAP: '热力图' }
const format = (value: unknown) => typeof value === 'number' ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value) : value ?? '—'
async function copy(sql: string) { try { await navigator.clipboard.writeText(sql); ElMessage.success('SQL 已复制') } catch { ElMessage.error('复制失败，可以手动选择 SQL 文本') } }
</script>
<template>
  <section class="chat-result">
    <header><h3>{{ result.title }}</h3><p>{{ result.summary }}</p></header>
    <div v-if="result.fallback" class="fallback-notice"><strong>{{ result.fallback.data_available ? '已使用受控模板完成查询' : '本次未获得可用数据' }}</strong><p>{{ result.fallback.reason }}</p></div>
    <div v-if="result.metrics.length" class="metric-grid">
      <div v-for="metric in result.metrics" :key="metric.key || metric.label"><span>{{ metric.label }}</span><strong>{{ format(metric.value) }} <small>{{ metric.unit }}</small></strong><span>{{ metric.note }}</span></div>
    </div>
    <div class="result-tabs" role="tablist" aria-label="查询结果">
      <button role="tab" :aria-selected="tab === 'analysis'" :class="{ active: tab === 'analysis' }" @click="tab = 'analysis'">图表与分析 <span v-if="result.charts.length">· {{ result.charts.length }} 张图</span></button>
      <button role="tab" :aria-selected="tab === 'table'" :class="{ active: tab === 'table' }" @click="tab = 'table'">数据明细</button>
    </div>
    <div v-if="tab === 'analysis'" class="analysis-layout" role="tabpanel">
      <section v-for="(chart, index) in result.charts" :key="index" class="chart-card">
        <div class="content-card-head"><strong>{{ chart.title }}</strong><span>{{ labels[chart.type] }}</span></div>
        <p class="chart-reason">{{ chart.reason }}</p><ResultChart :chart="chart" :rows="result.rows" />
      </section>
      <section class="analysis-card"><strong>数据分析</strong><ul><li v-for="item in result.analysis.insights" :key="item">{{ item }}</li></ul><p v-for="item in result.analysis.suggestions" :key="item" class="chart-reason">{{ item }}</p></section>
      <p v-if="!result.charts.length" class="chart-reason">{{ result.rows.length ? '当前结果不适合直接绘图，可查看指标和数据明细。' : '没有匹配数据，未生成图表。' }}</p>
    </div>
    <div v-else class="table-wrap" role="tabpanel"><el-table :data="result.rows" stripe empty-text="当前条件下没有匹配数据"><el-table-column v-for="column in result.columns" :key="column.key" :prop="column.key" :label="column.label + (column.unit && !column.label.includes(column.unit) ? `（${column.unit}）` : '')" min-width="128" show-overflow-tooltip /></el-table></div>
    <details v-if="result.sql_preview" class="query-sql"><summary>查看 SQL 依据</summary><button @click="copy(result.sql_preview)">复制 SQL</button><pre>{{ result.sql_preview }}</pre></details>
    <p class="result-meta">结果生成于 {{ result.data_as_of }} · {{ result.interpretation_source === 'RULE' ? '规则查询' : result.interpretation_source === 'DEEPSEEK' ? '模型规划' : '受控降级' }} · 仅反映本次返回数据；业务日期以结果字段为准</p>
  </section>
</template>
