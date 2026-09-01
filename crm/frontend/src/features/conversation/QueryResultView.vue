<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { QueryResult } from '../../app/types'
import ResultChart from './ResultChart.vue'
import { normalizeResult } from './messageText'
const props = defineProps<{ result: QueryResult }>()
const result = computed(() => normalizeResult(props.result))
const tab = ref(props.result.result_type === 'TABLE' ? 'table' : 'analysis')
// SSE阶段会原位替换payload；展示类型变化时Tab跟随，避免停留在与内容不符的页签。
watch(() => props.result.result_type, type => { tab.value = type === 'TABLE' ? 'table' : 'analysis' })
const sourceLabels: Record<string, string> = { RULE: '规则查询', DEEPSEEK: '模型规划', TEMPLATE_FALLBACK: '受控降级' }
const labels: Record<string, string> = { BAR: '柱状图', LINE: '折线图', AREA: '面积图', PIE: '构成图', SCATTER: '散点图', HEATMAP: '热力图' }
const format = (value: unknown) => typeof value === 'number' ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value) : value ?? '—'
async function copy(sql: string) { try { await navigator.clipboard.writeText(sql); ElMessage.success('SQL 已复制') } catch { ElMessage.error('复制失败，可以手动选择 SQL 文本') } }
// v1.5 结果导出：直接使用界面已脱敏的行生成 CSV（UTF-8 BOM 防 Excel 乱码），不经过任何未脱敏数据。
const CSV_NEWLINE = String.fromCharCode(13) + String.fromCharCode(10)
const CSV_BOM = String.fromCharCode(0xFEFF)
const CSV_DQ = String.fromCharCode(34)
function exportCsv() {
  const columns = result.value.columns
  if (!columns.length || !result.value.rows.length) return
  const needsQuote = new RegExp('[,"' + CSV_NEWLINE + ']')
  const escape = (value: unknown) => {
    const text = value === null || value === undefined ? '' : String(value)
    return needsQuote.test(text) ? CSV_DQ + text.split(CSV_DQ).join(CSV_DQ + CSV_DQ) + CSV_DQ : text
  }
  const lines = [columns.map(c => escape(c.label)).join(',')]
  for (const row of result.value.rows) lines.push(columns.map(c => escape(row[c.key])).join(','))
  const blob = new Blob([CSV_BOM + lines.join(CSV_NEWLINE)], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `查询结果-${new Date().toISOString().slice(0, 10)}.csv`
  link.click()
  URL.revokeObjectURL(url)
  ElMessage.success('已导出（数据与界面一致，均已脱敏）')
}
</script>
<template>
  <section class="chat-result">
    <header><h3>{{ result.title }}</h3><p>{{ result.summary }}</p></header>
    <p v-if="typeof result.total === 'number'" class="chart-reason" role="status">
      共 {{ format(result.total) }} 条 · 第 {{ result.page_no || 1 }} 页 · 每页 {{ result.page_size || result.rows.length }} 条
      <strong v-if="result.has_more"> · 后续仍有数据，本页不是全部结果</strong>
      <span v-else> · 已到最后一页</span>
    </p>
    <div v-if="result.fallback" class="fallback-notice"><strong>{{ result.fallback.data_available ? '已使用受控模板完成查询' : '本次未获得可用数据' }}</strong><p>{{ result.fallback.reason }}</p></div>
    <div v-if="result.metrics.length" class="metric-grid">
      <div v-for="metric in result.metrics" :key="metric.key || metric.label"><span>{{ metric.label }}</span><strong>{{ format(metric.value) }} <small>{{ metric.unit }}</small></strong><span>{{ metric.note }}</span></div>
    </div>
    <div class="result-tabs" role="tablist" aria-label="查询结果">
      <button role="tab" :aria-selected="tab === 'analysis'" :class="{ active: tab === 'analysis' }" @click="tab = 'analysis'">图表与分析 <span v-if="result.charts.length">· {{ result.charts.length }} 张图</span></button>
      <button role="tab" :aria-selected="tab === 'table'" :class="{ active: tab === 'table' }" @click="tab = 'table'">数据明细</button>
      <button v-if="tab === 'table' && result.rows.length" type="button" class="export-csv" aria-label="导出CSV（已脱敏）" title="导出数据与界面一致，均已脱敏" @click="exportCsv">导出 CSV</button>
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
    <p class="result-meta">{{ result.data_as_of ? `结果生成于 ${result.data_as_of}` : '旧版未记录结果日期' }} · {{ sourceLabels[result.interpretation_source] || '旧版未记录结果来源' }} · 仅反映本次返回数据；业务日期以结果字段为准</p>
  </section>
</template>
