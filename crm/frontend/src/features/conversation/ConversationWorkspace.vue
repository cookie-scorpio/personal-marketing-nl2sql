<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import {
  ArrowRight, Check, Clock, CopyDocument, DataAnalysis, Delete, Document,
  MagicStick, Promotion, Refresh, Search, TrendCharts, WarningFilled,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ApiError, apiRequest } from '../../app/api'
import type { HistoryItem, PageResult, SubmitQueryResponse, TaskStatus } from '../../app/types'
import ResultChart from './ResultChart.vue'

type ViewState = 'welcome' | 'running' | 'asking' | 'confirming' | 'success' | 'failed' | 'cancelled' | 'timedout'

const query = ref('')
const sessionId = ref(crypto.randomUUID())
const currentTask = ref<TaskStatus | null>(null)
const viewState = ref<ViewState>('welcome')
const submitting = ref(false)
const cancelling = ref(false)
const activeTask = computed(() => currentTask.value?.cancellable ?? ['running', 'asking', 'confirming'].includes(viewState.value))
const chartLabels: Record<string, string> = { BAR: '柱状图', LINE: '折线图', AREA: '面积图', PIE: '构成图', SCATTER: '散点图', HEATMAP: '热力图' }
function formatMetric(value: unknown) {
  return typeof value === 'number' ? new Intl.NumberFormat('zh-CN', { maximumFractionDigits: 2 }).format(value) : value ?? '—'
}
const selectedAnswer = ref('')
const customAnswer = ref('')
const errorMessage = ref('')
const showSql = ref(false)
const resultTab = ref<'analysis' | 'table'>('analysis')
const historyVisible = ref(false)
const historyLoading = ref(false)
const historyItems = ref<HistoryItem[]>([])
let pollGeneration = 0

const examples = [
  { icon: TrendCharts, title: '客户筛选', text: '找出资产超过50万元的高净值客户名单' },
  { icon: DataAnalysis, title: '交易分析', text: '统计近30天各机构客户交易金额' },
  { icon: Document, title: '产品持有', text: '分析持有理财产品的客户和持有规模' },
  { icon: WarningFilled, title: '营销转化', text: '分析本季度营销活动的触达和转化效果' },
]

const statusLabel = computed(() => {
  const labels: Record<string, string> = {
    RECEIVED: '请求已接收', INTENT_ANALYZING: '识别业务意图', SQL_GENERATING: '生成查询计划',
    VALIDATING: '安全与权限校验', EXECUTING: '查询营销数据', PACKAGING: '整理查询结果',
    REPAIRING: '修复查询 SQL', FALLING_BACK: '匹配固定查询模板',
  }
  return currentTask.value ? labels[currentTask.value.status] || currentTask.value.message : '等待查询'
})

const terminalStatuses = new Set(['ASKING', 'CONFIRMING', 'SUCCESS', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'DEGRADED'])

function useExample(text: string) {
  query.value = text
}

async function submitQuery() {
  if (!query.value.trim() || submitting.value || activeTask.value) return
  submitting.value = true
  errorMessage.value = ''
  showSql.value = false
  resultTab.value = 'analysis'
  currentTask.value = null
  viewState.value = 'running'
  try {
    const submitted = await apiRequest<SubmitQueryResponse>('/api/v1/queries', {
      method: 'POST',
      body: JSON.stringify({ session_id: sessionId.value, query_text: query.value.trim(), preferred_display: 'AUTO' }),
    })
    await beginPolling(submitted.task_id)
  } catch (exception) {
    failFrom(exception)
  } finally {
    submitting.value = false
  }
}

/** 每次开始新轮询都会使旧 generation 失效，避免重置页面后旧请求覆盖新状态。 */
async function beginPolling(taskId: string) {
  const generation = ++pollGeneration
  while (generation === pollGeneration) {
    try {
      const status = await apiRequest<TaskStatus>(`/api/v1/queries/${taskId}/status`)
      if (generation !== pollGeneration) return
      currentTask.value = status
      if (terminalStatuses.has(status.status)) {
        syncViewState(status)
        return
      }
      await new Promise(resolve => window.setTimeout(resolve, status.status === 'EXECUTING' ? 1000 : 650))
    } catch (exception) {
      if (generation !== pollGeneration) return
      failFrom(exception)
      return
    }
  }
}

function syncViewState(status: TaskStatus) {
  if (status.status === 'ASKING') {
    selectedAnswer.value = ''
    customAnswer.value = ''
    viewState.value = 'asking'
  } else if (status.status === 'CONFIRMING') {
    viewState.value = 'confirming'
  } else if (status.status === 'SUCCESS' || status.status === 'DEGRADED') {
    viewState.value = 'success'
  } else if (status.status === 'CANCELLED') {
    viewState.value = 'cancelled'
  } else if (status.status === 'TIMED_OUT') {
    viewState.value = 'timedout'
  } else {
    errorMessage.value = status.error?.message || status.message || '查询未完成'
    viewState.value = 'failed'
  }
}

async function submitClarification() {
  if (!currentTask.value?.question) return
  const answer = customAnswer.value.trim() || selectedAnswer.value
  if (!answer) return
  viewState.value = 'running'
  try {
    await apiRequest<SubmitQueryResponse>(`/api/v1/conversations/${sessionId.value}/messages`, {
      method: 'POST',
      body: JSON.stringify({
        task_id: currentTask.value.task_id,
        question_id: currentTask.value.question.question_id,
        answer_text: customAnswer.value.trim() || undefined,
        selected_options: customAnswer.value.trim() ? [] : [selectedAnswer.value],
      }),
    })
    await beginPolling(currentTask.value.task_id)
  } catch (exception) {
    failFrom(exception)
  }
}

async function decide(decision: 'CONFIRM' | 'REJECT') {
  if (!currentTask.value?.confirmation) return
  try {
    const status = await apiRequest<TaskStatus>(`/api/v1/queries/${currentTask.value.task_id}/confirmations`, {
      method: 'POST',
      body: JSON.stringify({ confirm_token: currentTask.value.confirmation.confirm_token, decision }),
    })
    currentTask.value = status
    if (decision === 'CONFIRM') {
      viewState.value = 'running'
      await beginPolling(status.task_id)
    } else {
      syncViewState(status)
    }
  } catch (exception) {
    failFrom(exception)
  }
}

async function cancelQuery(): Promise<boolean> {
  if (!currentTask.value || cancelling.value) return false
  cancelling.value = true
  try {
    const status = await apiRequest<TaskStatus>(`/api/v1/queries/${currentTask.value.task_id}/cancel`, { method: 'POST' })
    pollGeneration += 1
    currentTask.value = status
    syncViewState(status)
    return true
  } catch (exception) {
    ElMessage.error(exception instanceof ApiError ? exception.message : '取消请求未成功，请重试；后台任务可能仍在执行。')
    return false
  } finally { cancelling.value = false }
}

async function reset() {
  if (activeTask.value && !(await cancelQuery())) return
  pollGeneration += 1
  sessionId.value = crypto.randomUUID()
  currentTask.value = null
  query.value = ''
  errorMessage.value = ''
  showSql.value = false
  viewState.value = 'welcome'
}

function failFrom(exception: unknown) {
  errorMessage.value = exception instanceof ApiError ? exception.message : '请求失败，请稍后重试。'
  viewState.value = 'failed'
}

async function copySql() {
  const sql = currentTask.value?.result?.sql_preview
  if (!sql) return
  await navigator.clipboard.writeText(sql)
  ElMessage.success('SQL 已复制')
}

async function loadHistory() {
  historyVisible.value = true
  historyLoading.value = true
  try {
    const page = await apiRequest<PageResult<HistoryItem>>('/api/v1/query-history?page_no=1&page_size=30')
    historyItems.value = page.items
  } catch (exception) {
    ElMessage.error(exception instanceof ApiError ? exception.message : '历史记录加载失败')
  } finally {
    historyLoading.value = false
  }
}

async function reuseHistory(item: HistoryItem) {
  if (activeTask.value && !(await cancelQuery())) return
  pollGeneration += 1
  currentTask.value = null
  query.value = item.query_text
  historyVisible.value = false
  viewState.value = 'welcome'
}

async function deleteHistory(item: HistoryItem) {
  try {
    await ElMessageBox.confirm('删除后该记录将不再出现在查询历史中，任务与审计记录仍会保留。', '删除查询历史', {
      confirmButtonText: '删除记录', cancelButtonText: '保留', type: 'warning',
    })
    await apiRequest(`/api/v1/query-history/${item.history_id}`, { method: 'DELETE' })
    historyItems.value = historyItems.value.filter(candidate => candidate.history_id !== item.history_id)
    ElMessage.success('历史记录已删除')
  } catch (exception) {
    if (exception instanceof ApiError) ElMessage.error(exception.message)
  }
}

onUnmounted(() => { pollGeneration += 1 })
</script>

<template>
  <section class="conversation-card">
    <header class="conversation-head">
      <div>
        <p class="section-kicker"><MagicStick /> 智能查询助手</p>
        <h2>今天想了解哪些营销数据？</h2>
        <p>描述业务问题，系统会补全条件、生成受控 SQL 并解释结果。</p>
      </div>
      <button class="history-button" type="button" @click="loadHistory"><Clock /> 查询历史</button>
    </header>

    <div v-if="viewState === 'welcome'" class="welcome-content">
      <div class="example-grid">
        <button v-for="item in examples" :key="item.title" type="button" class="example-card" @click="useExample(item.text)">
          <span class="example-icon"><component :is="item.icon" /></span>
          <span><strong>{{ item.title }}</strong><small>{{ item.text }}</small></span>
          <ArrowRight />
        </button>
      </div>
      <div class="context-notice"><Check /><span><strong>查询边界已启用</strong>只读 SQL、数据范围和敏感字段策略均由服务端强制执行。</span></div>
    </div>

    <div v-else-if="viewState === 'running'" class="running-panel">
      <div class="progress-orbit"><MagicStick /></div>
      <p class="section-kicker">查询任务 {{ currentTask?.task_id?.slice(0, 8) || '创建中' }}</p>
      <h3>{{ statusLabel }}</h3>
      <p>{{ currentTask?.message || '正在建立安全查询上下文…' }}</p>
      <el-progress :percentage="currentTask?.progress || 8" :show-text="false" :stroke-width="7" />
      <div class="stage-row">
        <span :class="{ done: (currentTask?.progress || 0) >= 20 }">理解问题</span>
        <span :class="{ done: (currentTask?.progress || 0) >= 60 }">校验 SQL</span>
        <span :class="{ done: (currentTask?.progress || 0) >= 75 }">查询数据</span>
        <span :class="{ done: (currentTask?.progress || 0) >= 90 }">整理结果</span>
      </div>
      <div class="query-stop-actions">
        <el-button :disabled="!currentTask" :loading="cancelling" @click="cancelQuery">取消查询</el-button>
        <small>单次 SQL 最多执行 {{ currentTask?.execution_timeout_seconds ?? 60 }} 秒；取消后不再修复或降级。</small>
      </div>
    </div>

    <div v-else-if="viewState === 'asking' && currentTask?.question" class="clarification-panel">
      <div class="assistant-mark"><MagicStick /></div>
      <div class="clarification-main">
        <p class="section-kicker">需要补充 · 第 {{ currentTask.clarification_round + 1 }} 轮</p>
        <h3>{{ currentTask.question.prompt }}</h3>
        <div v-if="currentTask.question.options.length" class="answer-options">
          <button v-for="option in currentTask.question.options" :key="option" type="button"
                  :class="{ selected: selectedAnswer === option }" @click="selectedAnswer = option; customAnswer = ''">{{ option }}</button>
        </div>
        <el-input v-model="customAnswer" placeholder="也可以自行输入补充条件" maxlength="200" />
        <el-button type="primary" :disabled="!selectedAnswer && !customAnswer.trim()" @click="submitClarification">补充后继续</el-button>
        <el-button :loading="cancelling" @click="cancelQuery">取消查询</el-button>
      </div>
      <aside class="recognized-slots">
        <strong>已识别信息</strong>
        <span v-for="(value, key) in currentTask.question.recognized_slots" :key="key"><Check /> {{ key }}：{{ value }}</span>
      </aside>
    </div>

    <div v-else-if="viewState === 'confirming' && currentTask?.confirmation" class="risk-panel">
      <span class="risk-icon"><WarningFilled /></span>
      <div><p class="section-kicker">执行前确认</p><h3>该查询涉及较大的数据范围</h3><p>{{ currentTask.confirmation.message }}</p></div>
      <ul v-if="currentTask.confirmation.reasons?.length" class="risk-reasons"><li v-for="reason in currentTask.confirmation.reasons" :key="reason">{{ reason }}</li></ul>
      <div class="risk-actions"><el-button @click="decide('REJECT')">取消查询</el-button><el-button type="danger" @click="decide('CONFIRM')">确认并执行</el-button></div>
    </div>

    <div v-else-if="viewState === 'success' && currentTask?.result" class="result-panel">
      <div class="result-heading">
        <div><p class="section-kicker"><Check /> {{ currentTask.result.fallback ? '降级处理结果' : '查询完成' }}</p><h3>{{ currentTask.result.title }}</h3><p>{{ currentTask.result.summary }}</p></div>
        <span>数据截至 {{ currentTask.result.data_as_of }} · {{ currentTask.result.fallback ? '固定模板降级' : currentTask.result.interpretation_source === 'RULE' ? '规则快速识别' : 'DeepSeek智能识别' }}<template v-if="!currentTask.result.fallback"> {{ Math.round(currentTask.result.confidence * 100) }}%</template></span>
      </div>
      <div v-if="currentTask.result.fallback" class="fallback-notice" role="status">
        <strong>{{ currentTask.result.fallback.data_available ? '已使用固定模板，结果仍保留原问题的条件与数据权限。' : '未获得可用数据，未更换统计口径。' }}</strong>
        <p>{{ currentTask.result.fallback.reason }}</p>
        <p v-for="suggestion in currentTask.result.fallback.suggestions" :key="suggestion">{{ suggestion }}</p>
      </div>
      <div v-if="currentTask.result.metrics.length" class="metric-grid">
        <div v-for="metric in currentTask.result.metrics" :key="metric.key || metric.label"><span>{{ metric.label }}</span><strong>{{ formatMetric(metric.value) }} <small>{{ metric.unit }}</small></strong><span>{{ metric.note }}</span></div>
      </div>
      <div class="result-tabs" role="tablist" aria-label="结果展示方式">
        <button type="button" role="tab" :aria-selected="resultTab === 'analysis'" :class="{ active: resultTab === 'analysis' }" @click="resultTab = 'analysis'">图表与分析<span v-if="currentTask.result.charts.length"> · {{ currentTask.result.charts.length }} 张图</span></button>
        <button type="button" role="tab" :aria-selected="resultTab === 'table'" :class="{ active: resultTab === 'table' }" @click="resultTab = 'table'">数据明细</button>
      </div>
      <div v-if="resultTab === 'analysis'" class="analysis-layout" :class="{ 'no-chart': !currentTask.result.charts.length }">
        <section v-for="(chart, index) in currentTask.result.charts" :key="`${chart.type}-${chart.dimension_key}-${index}`" class="chart-card">
          <div class="content-card-head"><strong>{{ chart.title }}</strong><span>{{ chartLabels[chart.type] || chart.type }}</span></div>
          <p v-if="chart.reason" class="chart-reason">{{ chart.reason }}</p>
          <ResultChart :chart="chart" :rows="currentTask.result.rows" />
        </section>
        <section class="analysis-card">
          <div class="content-card-head"><strong>数据分析</strong><span>确定性计算</span></div>
          <p>{{ currentTask.result.analysis.overview }}</p>
          <ul><li v-for="insight in currentTask.result.analysis.insights" :key="insight">{{ insight }}</li></ul>
          <div v-for="suggestion in currentTask.result.analysis.suggestions" :key="suggestion" class="analysis-suggestion"><TrendCharts /><span>{{ suggestion }}</span></div>
        </section>
        <div v-if="!currentTask.result.charts.length" class="chart-empty">当前结果以单项指标或明细为主，暂不适合绘制图表。</div>
      </div>
      <div v-else class="table-wrap">
        <el-table :data="currentTask.result.rows" stripe empty-text="当前条件下没有匹配数据">
          <el-table-column v-for="column in currentTask.result.columns" :key="column.key" :prop="column.key" :label="column.label + (column.unit && !column.label.includes(column.unit) ? `（${column.unit}）` : '')" min-width="128" show-overflow-tooltip />
        </el-table>
      </div>
      <div v-if="currentTask.result.sql_preview" class="result-toolbar"><strong>查询依据</strong><button type="button" @click="showSql = !showSql"><CopyDocument />{{ showSql ? '收起 SQL' : '查看 SQL' }}</button></div>
      <div v-if="showSql" class="sql-panel"><div><span>已通过只读、权限和白名单校验</span><button type="button" @click="copySql">复制 SQL</button></div><pre>{{ currentTask.result.sql_preview }}</pre></div>
      <div class="result-foot"><span><Check /> 返回内容已按当前身份过滤并脱敏</span><el-button @click="reset">开始新的查询</el-button></div>
    </div>

    <div v-else-if="viewState === 'failed'" class="error-panel">
      <span><WarningFilled /></span><div><p class="section-kicker">查询未完成</p><h3>{{ errorMessage }}</h3><p>可以修改问题后重试；若持续失败，请记录页面上的任务编号。</p></div>
      <el-button @click="viewState = 'welcome'">修改问题</el-button>
    </div>

    <div v-else-if="viewState === 'cancelled' || viewState === 'timedout'" class="stopped-panel" role="status">
      <Clock /><div><h3>{{ viewState === 'cancelled' ? '查询已取消' : 'SQL 执行超时，查询已终止' }}</h3>
      <p>不会继续调用模型修复或执行降级查询。可以调整条件后重新发起查询。</p>
      <small v-if="currentTask">任务编号：{{ currentTask.task_id }}</small></div>
      <el-button @click="viewState = 'welcome'">修改问题</el-button>
    </div>

    <div class="composer" :class="{ compact: viewState !== 'welcome' }">
      <Search />
      <textarea v-model="query" rows="2" maxlength="1000" placeholder="例如：统计近30天各机构高净值客户的交易金额" @keydown.enter.exact.prevent="submitQuery" />
      <button type="button" class="send-button" :disabled="!query.trim() || submitting || activeTask" aria-label="发送查询" @click="submitQuery"><Promotion /></button>
    </div>
    <div class="composer-help"><span>Enter 发送 · 最多 1000 字</span><button v-if="viewState !== 'welcome'" type="button" :disabled="cancelling || (activeTask && !currentTask)" @click="reset"><Refresh /> {{ activeTask ? '取消并清空会话' : '清空会话' }}</button></div>

    <el-drawer v-model="historyVisible" title="查询历史" size="420px">
      <div v-loading="historyLoading" class="history-list">
        <div v-if="!historyLoading && !historyItems.length" class="history-empty">完成一次查询后，历史记录会显示在这里。</div>
        <article v-for="item in historyItems" :key="item.history_id">
          <button class="history-copy" type="button" @click="reuseHistory(item)"><strong>{{ item.query_text }}</strong><span>{{ item.result_summary || item.status_code }}</span><small>{{ new Date(item.created_at).toLocaleString('zh-CN') }}</small></button>
          <button class="history-delete" type="button" aria-label="删除历史" @click="deleteHistory(item)"><Delete /></button>
        </article>
      </div>
    </el-drawer>
  </section>
</template>
