<script setup lang="ts">
/**
 * 数据回流页：评测样本的筛选与沉淀工作台。
 * 任务视角（默认）：一个终态任务一行，对应一个潜在评测样本，终态事件作为审核锚点；
 * 事实明细视图：全量审计事实逐条浏览，支持事件类型等组合筛选，过程事实供深挖；
 * 系统与审计事实（登录、运行异常等无任务关联）只出现在事实明细视图，不属于评测样本，无需逐条忽略。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { acceptCandidate, fetchFacts, fetchTaskFacts, fetchTaskView, ignoreCandidate } from '../api'
import { usePolling } from '../usePolling'
import { EVENT_LABELS, eventLabel, intentKeys, intentLabel, statusLabel, translateSummary } from '../eventLabels'
import type { FactItem, FactPage, TaskFact, TaskItem, TaskPage } from '../types'

type FeedbackRow = FactItem | TaskItem

const REFRESH_MS = 30000
const PAGE_SIZE = 20

// ---- 视图：任务视角（默认，一个任务一行）或事实明细（全量事实逐条） ----
const view = ref<'tasks' | 'facts'>('tasks')

// ---- 筛选条件：任意条件变化回到第一页并立即重查；hours=0 表示不限时间窗口 ----
const reviewStatus = ref('all')
const hours = ref(168)
const taskStatuses = ref<string[]>([])
const eventType = ref('')
const intent = ref('')
const keyword = ref('')
const keywordInput = ref('')
const pageNo = ref(1)

const { data, error, refresh } = usePolling(
  (): Promise<TaskPage | FactPage> => view.value === 'tasks'
    ? fetchTaskView({
        status: reviewStatus.value,
        pageNo: pageNo.value,
        pageSize: PAGE_SIZE,
        hours: hours.value,
        taskStatus: taskStatuses.value.join(',') || undefined,
        intent: intent.value || undefined,
        keyword: keyword.value || undefined,
      })
    : fetchFacts({
        status: reviewStatus.value,
        pageNo: pageNo.value,
        pageSize: PAGE_SIZE,
        hours: hours.value,
        eventType: eventType.value || undefined,
        taskStatus: taskStatuses.value.join(',') || undefined,
        intent: intent.value || undefined,
        keyword: keyword.value || undefined,
      }),
  REFRESH_MS,
)

function resetAndRefresh() {
  pageNo.value = 1
  void refresh(true)
}
watch([view, reviewStatus, hours, taskStatuses, eventType, intent], resetAndRefresh)
watch(view, () => { selected.value = [] })
function search() {
  keyword.value = keywordInput.value.trim()
  resetAndRefresh()
}

const rows = computed<FeedbackRow[]>(() => (data.value?.items ?? []) as FeedbackRow[])
const total = computed(() => data.value?.total ?? 0)
const selected = ref<FeedbackRow[]>([])

// ---- 筛选选项：任务终态覆盖全部落库状态；事件类型筛选仅在事实明细视图生效 ----
const TASK_STATUS_OPTIONS = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'DEGRADED', label: '降级完成' },
  { value: 'FAILED', label: '失败' },
  { value: 'TIMED_OUT', label: '超时' },
  { value: 'CANCELLED', label: '已取消' },
  { value: 'ASKING', label: '等待澄清' },
]
const EVENT_TYPE_OPTIONS = Object.keys(EVENT_LABELS)
const INTENT_OPTIONS = intentKeys()

const eventTypeTag = (key: string) => eventLabel(key)
const summaryText = (row: FeedbackRow) => translateSummary(row.event_summary ?? '')
const occurredAt = (row: { occurred_at: string }) => (row.occurred_at ?? '').replace('T', ' ').slice(0, 19)
const decisionTag = (row: FeedbackRow) =>
  row.decision === 'ACCEPTED' ? '已采纳' : row.decision === 'IGNORED' ? '已忽略' : '未处理'
const isGoldCandidate = (row: FeedbackRow) => row.task_status === 'SUCCESS'
/** 审核操作锚定终态事件；没有终态事件的历史任务只能查看，不能采纳或忽略。 */
const reviewable = (row: FeedbackRow) => Boolean(row.event_id) && !row.decision
const selectable = (row: FeedbackRow) => reviewable(row)

// ---- 采纳 / 转金标：共用一个金标对话框，成功样本自动预填实际执行 SQL ----
const acceptVisible = ref(false)
const submitting = ref(false)
const acceptMode = ref<'accept' | 'gold'>('accept')
const form = ref({ eventId: '', question: '', sql: '', note: '', intent: '' })

function openAccept(row: FeedbackRow) {
  acceptMode.value = isGoldCandidate(row) ? 'gold' : 'accept'
  form.value = {
    eventId: row.event_id ?? '',
    question: row.question_text ?? '',
    sql: row.sql_text ?? '',
    note: acceptMode.value === 'gold' ? '来源：成功任务转金标' : row.error_text ?? '',
    // 金标意图默认取该任务的实际判定，审核人可修正；不标注则不参与意图准确率。
    intent: row.task_intent ?? '',
  }
  acceptVisible.value = true
}

async function submitAccept() {
  if (!form.value.question.trim() || !form.value.sql.trim()) {
    ElMessage.warning('问题原文与期望 SQL 都不能为空')
    return
  }
  submitting.value = true
  try {
    await acceptCandidate(form.value.eventId, {
      question_text: form.value.question,
      expected_sql: form.value.sql,
      note: form.value.note || undefined,
      intent_code: form.value.intent || undefined,
    })
    acceptVisible.value = false
    ElMessage.success(acceptMode.value === 'gold' ? '成功样本已转金标进入评测集草稿' : '已采纳进入评测集草稿')
    await refresh(true)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '采纳失败')
  } finally {
    submitting.value = false
  }
}

// ---- 忽略：单条或批量；批量逐条提交，部分失败时汇报成功数量 ----
async function submitIgnore(row: FeedbackRow) {
  try {
    const { value } = await ElMessageBox.prompt('可填写忽略原因（可选）', '忽略该条记录', {
      confirmButtonText: '忽略',
      cancelButtonText: '取消',
      inputPlaceholder: '忽略原因',
    })
    await ignoreCandidate(row.event_id ?? '', value || undefined)
    ElMessage.success('已忽略该条记录')
    await refresh(true)
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(cause instanceof Error ? cause.message : '忽略失败')
  }
}

const batchIgnoring = ref(false)
async function batchIgnore() {
  const targets = selected.value.filter(row => reviewable(row))
  if (!targets.length) {
    ElMessage.warning('请先勾选可审核的记录')
    return
  }
  batchIgnoring.value = true
  let done = 0
  try {
    for (const row of targets) {
      try {
        await ignoreCandidate(row.event_id ?? '', '批量忽略')
        done += 1
      } catch { /* 单条失败不中断整批 */ }
    }
    ElMessage.success(`已忽略 ${done}/${targets.length} 条`)
    selected.value = []
    await refresh(true)
  } finally {
    batchIgnoring.value = false
  }
}

// ---- 任务详情抽屉：回看该任务的全链路事实时间线 ----
const drawerVisible = ref(false)
const drawerLoading = ref(false)
const drawerTaskId = ref('')
const drawerFacts = ref<TaskFact[]>([])

async function openTask(row: FeedbackRow) {
  if (!row.task_id) return
  drawerTaskId.value = row.task_id
  drawerVisible.value = true
  drawerLoading.value = true
  try {
    drawerFacts.value = await fetchTaskFacts(row.task_id)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '任务事实加载失败')
    drawerFacts.value = []
  } finally {
    drawerLoading.value = false
  }
}
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`数据加载失败：${error}`" :closable="false" show-icon />
    <div class="quality-toolbar quality-toolbar-column">
      <el-radio-group v-model="view" size="small">
        <el-radio-button value="tasks">任务视角</el-radio-button>
        <el-radio-button value="facts">事实明细</el-radio-button>
      </el-radio-group>
      <el-radio-group v-model="reviewStatus" size="small">
        <el-radio-button value="all">全部</el-radio-button>
        <el-radio-button value="pending">未处理</el-radio-button>
        <el-radio-button value="ACCEPTED">已采纳</el-radio-button>
        <el-radio-button value="IGNORED">已忽略</el-radio-button>
      </el-radio-group>
      <el-select v-model="hours" size="small" class="filter-hours">
        <el-option :value="24" label="近 24 小时" />
        <el-option :value="168" label="近 7 天" />
        <el-option :value="720" label="近 30 天" />
        <el-option :value="0" label="全部时间" />
      </el-select>
      <el-select v-model="taskStatuses" size="small" multiple collapse-tags collapse-tags-tooltip
        placeholder="任务终态" clearable class="filter-status">
        <el-option v-for="option in TASK_STATUS_OPTIONS" :key="option.value" :value="option.value" :label="option.label" />
      </el-select>
      <el-select v-if="view === 'facts'" v-model="eventType" size="small" placeholder="事件类型" clearable filterable class="filter-type">
        <el-option v-for="key in EVENT_TYPE_OPTIONS" :key="key" :value="key" :label="eventLabel(key)" />
      </el-select>
      <el-select v-model="intent" size="small" placeholder="意图" clearable class="filter-intent">
        <el-option v-for="key in INTENT_OPTIONS" :key="key" :value="key" :label="intentLabel(key)" />
      </el-select>
      <el-input v-model="keywordInput" size="small" placeholder="搜索问题原文或 SQL" clearable
        class="filter-keyword" @keyup.enter="search" @clear="search">
        <template #append>
          <el-button :icon="Search" @click="search" />
        </template>
      </el-input>
    </div>
    <div class="quality-toolbar">
      <small class="quality-toolbar-hint">
        <template v-if="view === 'tasks'">
          任务视角：一个终态任务一行，对应一个评测样本；成功任务转金标，失败任务补写金标后采纳。过程事实请在"任务详情"或"事实明细"中查看。
        </template>
        <template v-else>
          事实明细：全量审计事实逐条浏览。登录、运行异常等系统事实没有任务维度，不属于评测样本，无需审核。
        </template>
      </small>
      <el-button v-if="selected.length" size="small" :loading="batchIgnoring" @click="batchIgnore">
        批量忽略（{{ selected.length }}）
      </el-button>
    </div>

    <section class="panel-card">
      <!-- 任务视角：一任务一行，终态事件为审核锚点 -->
      <el-table v-if="view === 'tasks'" :data="rows" size="small" @selection-change="selected = $event">
        <el-table-column type="selection" width="36" :selectable="selectable" />
        <el-table-column label="问题原文" min-width="220">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="row.question_text ?? ''">{{ row.question_text || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="任务终态" width="96">
          <template #default="{ row }">
            <el-tag size="small" effect="plain"
              :type="row.task_status === 'SUCCESS' ? 'success' : row.task_status === 'DEGRADED' ? 'warning' : 'danger'">
              {{ statusLabel(row.task_status) || row.task_status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="意图" width="100">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="intentLabel(row.task_intent)">{{ intentLabel(row.task_intent) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发生时间" width="160">
          <template #default="{ row }">{{ occurredAt(row) }}</template>
        </el-table-column>
        <el-table-column label="审核状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.decision === 'ACCEPTED'" size="small" type="success">已采纳</el-tag>
            <el-tag v-else-if="row.decision === 'IGNORED'" size="small" type="info">已忽略</el-tag>
            <el-tag v-else size="small" type="warning">未处理</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <template v-if="reviewable(row)">
              <el-button size="small" :type="isGoldCandidate(row) ? 'success' : 'primary'" link
                @click="openAccept(row)">{{ isGoldCandidate(row) ? '转金标' : '采纳' }}</el-button>
              <el-button size="small" link @click="submitIgnore(row)">忽略</el-button>
            </template>
            <small v-else-if="row.decision" class="review-note">{{ row.review_note || decisionTag(row) }}</small>
            <small v-else class="review-note" title="该任务没有终态事件，无法沉淀为评测样本">无审核锚点</small>
            <el-button v-if="row.task_id" size="small" link type="info" @click="openTask(row)">任务详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 事实明细：全量审计事实逐条浏览 -->
      <el-table v-else :data="rows" size="small" @selection-change="selected = $event">
        <el-table-column type="selection" width="36" :selectable="selectable" />
        <el-table-column label="类型" width="140">
          <template #default="{ row }">
            <el-tag size="small" type="warning" effect="light" :title="eventTypeTag(row.event_type ?? '')">{{ eventTypeTag(row.event_type ?? '') || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="200">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="summaryText(row)">{{ summaryText(row) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="问题原文" min-width="200">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="row.question_text ?? ''">{{ row.question_text || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="任务终态" width="96">
          <template #default="{ row }">{{ statusLabel(row.task_status) || '—' }}</template>
        </el-table-column>
        <el-table-column label="意图" width="100">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="intentLabel(row.task_intent)">{{ intentLabel(row.task_intent) || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发生时间" width="160">
          <template #default="{ row }">{{ occurredAt(row) }}</template>
        </el-table-column>
        <el-table-column label="审核状态" width="90">
          <template #default="{ row }">
            <el-tag v-if="row.decision === 'ACCEPTED'" size="small" type="success">已采纳</el-tag>
            <el-tag v-else-if="row.decision === 'IGNORED'" size="small" type="info">已忽略</el-tag>
            <el-tag v-else size="small" type="warning">未处理</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <template v-if="reviewable(row)">
              <el-button size="small" :type="isGoldCandidate(row) ? 'success' : 'primary'" link
                @click="openAccept(row)">{{ isGoldCandidate(row) ? '转金标' : '采纳' }}</el-button>
              <el-button size="small" link @click="submitIgnore(row)">忽略</el-button>
            </template>
            <small v-else-if="row.decision" class="review-note">{{ row.review_note || decisionTag(row) }}</small>
            <small v-else class="review-note" title="系统与审计事实没有任务维度，不属于评测样本">系统事实</small>
            <el-button v-if="row.task_id" size="small" link type="info" @click="openTask(row)">任务详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-if="!rows.length" :description="view === 'tasks' ? '当前筛选下没有终态任务' : '当前筛选下没有任务事实'" :image-size="88" />
      <el-pagination
        v-if="total > PAGE_SIZE"
        v-model:current-page="pageNo"
        layout="prev, pager, next"
        :page-size="PAGE_SIZE"
        :total="total"
        class="quality-pagination"
        @current-change="() => refresh(true)"
      />
    </section>

    <el-dialog v-model="acceptVisible" :title="acceptMode === 'gold' ? '成功样本转金标' : '采纳失败样本进入评测集草稿'" width="640">
      <el-form label-position="top">
        <el-form-item label="问题原文（评测输入）">
          <el-input v-model="form.question" type="textarea" :rows="2" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="期望 SQL（评测金标）">
          <el-input v-model="form.sql" type="textarea" :rows="6" class="sql-input" maxlength="30000" />
        </el-form-item>
        <el-form-item label="金标意图（可选，默认取该任务的实际判定）">
          <el-select v-model="form.intent" placeholder="不标注则不参与意图准确率" clearable>
            <el-option v-for="key in INTENT_OPTIONS" :key="key" :value="key" :label="intentLabel(key)" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注（可选，记录失败原因或关注点）">
          <el-input v-model="form.note" type="textarea" :rows="2" maxlength="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="acceptVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAccept">确认采纳</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="drawerVisible" title="任务全链路事实时间线" size="480">
      <p class="drawer-task">任务编号：{{ drawerTaskId }}</p>
      <el-empty v-if="!drawerLoading && !drawerFacts.length" description="该任务没有落库事实" :image-size="72" />
      <ol v-else class="task-timeline" v-loading="drawerLoading">
        <li v-for="fact in drawerFacts" :key="fact.event_id" class="task-timeline-item">
          <div class="task-timeline-head">
            <el-tag size="small" effect="plain" :type="fact.decision === 'ACCEPTED' ? 'success' : fact.decision === 'IGNORED' ? 'info' : 'warning'">
              {{ eventTypeTag(fact.event_type) }}
            </el-tag>
            <small>{{ occurredAt(fact) }}</small>
          </div>
          <p class="task-timeline-summary">{{ translateSummary(fact.event_summary) || '—' }}</p>
        </li>
      </ol>
    </el-drawer>
  </div>
</template>
