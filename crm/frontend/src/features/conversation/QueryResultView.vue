<script setup lang="ts">
/** 以服务端列语义展示结果；页面展示和 CSV 导出只接触已经过脱敏的响应数据。 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { QueryResult } from '../../app/types'
import ResultChart from './ResultChart.vue'
import { normalizeResult } from './messageText'
const props = withDefaults(defineProps<{ result: QueryResult; stream?: boolean }>(), { stream: false })
const emit = defineEmits<{ 'stream-progress': []; 'stream-complete': [] }>()
const result = computed(() => normalizeResult(props.result))
const tab = ref(props.result.result_type === 'TABLE' ? 'table' : 'analysis')
const STREAM_FRAME_MS = 32
const STREAM_MAX_FRAMES = 48
const revealedCharacters = ref(Number.MAX_SAFE_INTEGER)
const streaming = ref(false)
const reducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)')
let streamTimer: number | undefined
let progressFrames = 0

/**
 * 摘要、概览和洞察共用一条字符进度，保证文字按阅读顺序依次出现，而不是多段同时闪动。
 * Array.from 按 Unicode 码点切分，中文和常见表情不会被普通字符串下标拆成半个字符。
 */
const analysisOverview = computed(() => {
  const overview = result.value.analysis.overview.trim()
  return overview && overview !== result.value.summary.trim() ? overview : ''
})
const narrativeSegments = computed(() => [
  result.value.summary,
  analysisOverview.value,
  ...result.value.analysis.insights,
])
const visibleNarrative = computed(() => {
  let remaining = revealedCharacters.value
  return narrativeSegments.value.map((segment) => {
    const characters = Array.from(segment)
    const visible = characters.slice(0, Math.max(0, remaining)).join('')
    remaining -= characters.length
    return visible
  })
})
const visibleSummary = computed(() => visibleNarrative.value[0] || '')
const visibleOverview = computed(() => visibleNarrative.value[1] || '')
const visibleInsights = computed(() => visibleNarrative.value.slice(2))

function clearStreamTimer() {
  if (streamTimer !== undefined) window.clearInterval(streamTimer)
  streamTimer = undefined
}

/**
 * 结构化数据已经完整到达后才开始视觉流式展示。本方法只控制界面呈现，不改写服务端结果；
 * 单次动画最多约 1.5 秒，长回答会自动增加每帧字符数，避免为了动画拖慢用户获取结果。
 */
function startStream() {
  clearStreamTimer()
  const total = narrativeSegments.value.reduce((count, segment) => count + Array.from(segment).length, 0)
  if (!props.stream || reducedMotion.matches || total === 0) {
    streaming.value = false
    revealedCharacters.value = total
    // 即使无需动画，也要通知父组件释放“本次实时结果”标记，避免无动画偏好下残留状态。
    if (props.stream) window.queueMicrotask(() => emit('stream-complete'))
    return
  }

  streaming.value = true
  revealedCharacters.value = 0
  progressFrames = 0
  const charactersPerFrame = Math.max(1, Math.ceil(total / STREAM_MAX_FRAMES))
  streamTimer = window.setInterval(() => {
    revealedCharacters.value = Math.min(total, revealedCharacters.value + charactersPerFrame)
    progressFrames += 1
    // 每四帧通知父组件一次即可保持跟随阅读，避免每 32ms 都触发滚动测量。
    if (progressFrames % 4 === 0) emit('stream-progress')
    if (revealedCharacters.value >= total) {
      clearStreamTimer()
      streaming.value = false
      emit('stream-complete')
    }
  }, STREAM_FRAME_MS)
}

/** 用户在动画期间开启“减少动态效果”时，立即展示完整结果。 */
function handleMotionPreference() {
  if (!reducedMotion.matches || !streaming.value) return
  clearStreamTimer()
  revealedCharacters.value = narrativeSegments.value.reduce((count, segment) => count + Array.from(segment).length, 0)
  streaming.value = false
  emit('stream-complete')
}

// SSE 阶段会原位替换 payload；展示类型变化时页签跟随，避免停留在与内容不符的页面。
watch(() => props.result.result_type, type => { tab.value = type === 'TABLE' ? 'table' : 'analysis' })
// 只在父组件改变本次实时结果标记时启动；同一终态经 SSE 重放时即使对象引用变化，也不能从头播放。
watch(() => props.stream, startStream, { immediate: true })
onMounted(() => reducedMotion.addEventListener('change', handleMotionPreference))
onBeforeUnmount(() => {
  clearStreamTimer()
  reducedMotion.removeEventListener('change', handleMotionPreference)
})
const labels: Record<string, string> = { BAR: '柱状图', LINE: '折线图', AREA: '面积图', PIE: '构成图', SCATTER: '散点图', HEATMAP: '热力图' }
const format = (value: unknown) => typeof value === 'number' ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value) : value ?? '—'
// 直接使用界面已脱敏的行生成 CSV；UTF-8 BOM 用于避免 Excel 打开中文时乱码。
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
  <section class="chat-result" :aria-busy="streaming">
    <header>
      <h3>{{ result.title }}</h3>
      <p class="result-summary">
        <span v-if="streaming" class="result-stream-space" aria-hidden="true">{{ result.summary }}</span>
        <span v-if="streaming" class="result-stream-summary-value" aria-hidden="true">{{ visibleSummary }}<i class="stream-caret" /></span>
        <span v-else>{{ result.summary }}</span>
        <!-- 逐帧字符对读屏软件过于嘈杂；无障碍树直接提供完整且稳定的摘要。 -->
        <span v-if="streaming" class="result-stream-reader">{{ result.summary }}</span>
      </p>
    </header>
    <p v-if="typeof result.total === 'number'" class="chart-reason result-structured-content" :class="{ 'is-stream-pending': streaming }" role="status">
      共 {{ format(result.total) }} 条 · 第 {{ result.page_no || 1 }} 页 · 每页 {{ result.page_size || result.rows.length }} 条
      <strong v-if="result.has_more"> · 后续仍有数据，本页不是全部结果</strong>
      <span v-else> · 已到最后一页</span>
    </p>
    <div v-if="result.fallback" class="fallback-notice result-structured-content" :class="{ 'is-stream-pending': streaming }"><strong>{{ result.fallback.data_available ? '已使用受控模板完成查询' : '本次未获得可用数据' }}</strong><p>{{ result.fallback.reason }}</p></div>
    <section v-if="result.metrics.length" class="primary-results result-structured-content" :class="{ 'is-stream-pending': streaming }" aria-label="主要查询结果">
      <h4>主要查询结果</h4>
      <div class="metric-grid">
        <div v-for="metric in result.metrics" :key="metric.key || metric.label"><span>{{ metric.label }}</span><strong>{{ format(metric.value) }} <small>{{ metric.unit }}</small></strong><span>{{ metric.note }}</span></div>
      </div>
    </section>
    <div class="result-tabs" role="tablist" aria-label="查询结果">
      <button role="tab" :aria-selected="tab === 'analysis'" :class="{ active: tab === 'analysis' }" @click="tab = 'analysis'">图表与分析 <span v-if="result.charts.length">· {{ result.charts.length }} 张图</span></button>
      <button role="tab" :aria-selected="tab === 'table'" :class="{ active: tab === 'table' }" @click="tab = 'table'">数据明细</button>
      <button v-if="tab === 'table' && result.rows.length && !streaming" type="button" class="export-csv" aria-label="导出CSV（已脱敏）" title="导出数据与界面一致，均已脱敏" @click="exportCsv">导出 CSV</button>
    </div>
    <div v-if="tab === 'analysis'" class="analysis-layout" role="tabpanel">
      <section v-for="(chart, index) in result.charts" :key="index" class="chart-card">
        <div class="content-card-head"><strong>{{ chart.title }}</strong><span>{{ labels[chart.type] }}</span></div>
        <p class="chart-reason">{{ chart.reason }}</p>
        <!-- 图表数据不可按字符拆分；文字完成后再一次挂载，避免图表在流式过程中反复布局。 -->
        <div v-if="streaming" class="result-visual-placeholder" aria-hidden="true"><span>正在准备图表…</span></div>
        <ResultChart v-else class="result-structured-enter" :chart="chart" :rows="result.rows" />
      </section>
      <section v-if="analysisOverview || result.analysis.insights.length" class="analysis-card"><strong>数据分析</strong>
        <p v-if="analysisOverview" :class="{ 'result-stream-reserve': streaming }">
          <span v-if="streaming" class="result-stream-space" aria-hidden="true">{{ analysisOverview }}</span>
          <span v-if="streaming" class="result-stream-overview-value" aria-hidden="true">{{ visibleOverview }}</span>
          <span v-else>{{ analysisOverview }}</span>
        </p>
        <ul><li v-for="(item, index) in result.analysis.insights" :key="item" :class="{ 'result-stream-list-item': streaming, 'is-awaiting': streaming && !visibleInsights[index] }">
          <span v-if="streaming" class="result-stream-space" aria-hidden="true">{{ item }}</span>
          <span v-if="streaming" class="result-stream-value" aria-hidden="true">{{ visibleInsights[index] }}</span>
          <span v-else>{{ item }}</span>
        </li></ul>
        <span v-if="streaming" class="result-stream-reader">{{ [analysisOverview, ...result.analysis.insights].filter(Boolean).join('。') }}</span>
      </section>
    </div>
    <div v-else class="table-wrap" role="tabpanel">
      <div v-if="streaming" class="result-table-placeholder" aria-hidden="true"><span>正在准备数据明细…</span></div>
      <el-table v-else class="result-structured-enter" :data="result.rows" stripe empty-text="当前条件下没有匹配数据"><el-table-column v-for="column in result.columns" :key="column.key" :prop="column.key" :label="column.label + (column.unit && !column.label.includes(column.unit) ? `（${column.unit}）` : '')" min-width="128" show-overflow-tooltip /></el-table>
    </div>
    <p class="result-meta result-structured-content" :class="{ 'is-stream-pending': streaming }">{{ result.data_as_of ? `结果生成于 ${result.data_as_of}` : '旧版未记录结果日期' }}</p>
  </section>
</template>
