<script setup lang="ts">
/**
 * 评测管理页：维护评测集草稿、发布版本、查看历次评测运行与多维度报告。
 * 发布后内容不可修改，自动触发一次评测运行；发布版本支持手动重跑。
 */
import { computed, onBeforeUnmount, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  addDatasetItem, deleteDatasetItem, fetchDataset, fetchDatasets, fetchRun, fetchRuns,
  publishDataset, startRun, updateDatasetItem,
} from '../api'
import type { DatasetDetail, DatasetView, RunDetail, RunView } from '../types'

const datasets = ref<DatasetView[]>([])
const draft = ref<DatasetDetail>()
const runs = ref<RunView[]>([])
const runsTotal = ref(0)
const selectedDataset = ref<DatasetDetail>()
const runDetail = ref<RunDetail>()
const detailVisible = ref(false)
const itemDialogVisible = ref(false)
/** 草稿条目表单；id 有值表示编辑既有条目，为空表示新增。 */
const itemForm = ref({ id: undefined as number | undefined, question: '', sql: '', note: '' })
const busy = ref(false)
const datasetsError = ref('')
let runsTimer: number | undefined

const publishedDatasets = computed(() => datasets.value.filter(item => item.status === 'PUBLISHED'))
const draftItems = computed(() => draft.value?.items ?? [])

async function loadAll(): Promise<void> {
  try {
    datasets.value = await fetchDatasets()
    const currentId = draft.value?.id
      ?? datasets.value.find(item => item.status === 'DRAFT')?.id
    if (currentId) draft.value = await fetchDataset(currentId)
    await loadRuns()
    datasetsError.value = ''
  } catch (cause) {
    datasetsError.value = cause instanceof Error ? cause.message : '评测数据加载失败'
  }
}

async function loadRuns(): Promise<void> {
  const page = await fetchRuns(1, 50)
  runs.value = page.items
  runsTotal.value = page.total
  scheduleRunsPolling()
}

/** 有运行处于进行中时保持 3 秒轻轮询，全部结束后停止定时器。 */
function scheduleRunsPolling(): void {
  const running = runs.value.some(run => run.status === 'PENDING' || run.status === 'RUNNING')
  if (running && runsTimer === undefined) {
    runsTimer = window.setInterval(() => {
      const stillRunning = runs.value.some(run => run.status === 'PENDING' || run.status === 'RUNNING')
      if (stillRunning) void loadRuns()
      else stopRunsPolling()
    }, 3000)
  } else if (!running) stopRunsPolling()
}
function stopRunsPolling(): void {
  window.clearInterval(runsTimer)
  runsTimer = undefined
}
onBeforeUnmount(stopRunsPolling)

function openDataset(dataset: DatasetView): void {
  // 草稿已在左侧维护区展示；这里用于查看历史发布版本的冻结内容。
  if (dataset.id === draft.value?.id) return
  void fetchDataset(dataset.id).then(detail => {
    selectedDataset.value = detail
  }).catch(cause => ElMessage.error(cause instanceof Error ? cause.message : '评测集加载失败'))
}

function openItemDialog(): void {
  itemForm.value = { id: undefined, question: '', sql: '', note: '' }
  itemDialogVisible.value = true
}
function openEditDialog(itemId: number): void {
  const item = draftItems.value.find(candidate => candidate.id === itemId)
  if (!item) return
  itemForm.value = { id: item.id, question: item.question_text, sql: item.expected_sql, note: item.note ?? '' }
  itemDialogVisible.value = true
}

async function submitItem(): Promise<void> {
  const target = itemForm.value
  if (!target.question.trim() || !target.sql.trim()) {
    ElMessage.warning('问题原文与期望 SQL 都不能为空')
    return
  }
  busy.value = true
  try {
    if (target.id) await updateDatasetItem(target.id, {
      question_text: target.question, expected_sql: target.sql, note: target.note || undefined,
    })
    else await addDatasetItem({ question_text: target.question, expected_sql: target.sql, note: target.note || undefined })
    itemDialogVisible.value = false
    await loadAll()
    ElMessage.success(target.id ? '条目已更新' : '条目已加入草稿')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '保存失败')
  } finally {
    busy.value = false
  }
}

async function removeItem(itemId: number): Promise<void> {
  try {
    await ElMessageBox.confirm('确认从草稿中删除该条目？', '删除条目', { type: 'warning' })
  } catch {
    return
  }
  try {
    await deleteDatasetItem(itemId)
    await loadAll()
    ElMessage.success('条目已删除')
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '删除失败')
  }
}

async function publishDraft(): Promise<void> {
  if (!draft.value) return
  try {
    await ElMessageBox.confirm(
      `发布后评测集内容不可修改，并自动对系统运行一次评测（共 ${draft.value.item_count} 条，真实调用模型）。确认发布？`,
      '发布评测集',
      { type: 'warning', confirmButtonText: '发布并开始评测', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  busy.value = true
  try {
    const result = await publishDataset(draft.value.id)
    ElMessage.success(`已发布版本 ${result.dataset.version}，评测运行 #${result.run_id} 已启动`)
    await loadAll()
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '发布失败')
  } finally {
    busy.value = false
  }
}

async function rerun(datasetId: number): Promise<void> {
  try {
    const result = await startRun(datasetId)
    ElMessage.success(`评测运行 #${result.run_id} 已启动`)
    await loadAll()
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '评测启动失败')
  }
}

async function openRun(runId: number): Promise<void> {
  try {
    runDetail.value = await fetchRun(runId)
    detailVisible.value = true
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '评测报告加载失败')
  }
}

const OUTCOME_LABELS: Record<string, string> = {
  PASSED: '通过', SQL_MISMATCH: 'SQL 不一致', RESULT_MISMATCH: '结果不一致', EXECUTION_FAILED: '执行失败',
  VALIDATION_FAILED: '校验失败', INTERPRET_FAILED: '解释失败', CLARIFICATION_NEEDED: '发起澄清', UNKNOWN: '未知',
}
const dimensionMark = (value: boolean | null) => (value === null ? '—' : value ? '✓' : '✗')

void loadAll()
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="datasetsError" type="error" :title="datasetsError" :closable="false" show-icon />
    <div class="panel-grid panel-grid-evaluation">
      <section class="panel-card">
        <header class="panel-card-head">
          <h3>评测集草稿（可编辑）</h3>
          <div>
            <el-button size="small" @click="openItemDialog">添加条目</el-button>
            <el-button size="small" type="primary" :disabled="!draftItems.length" :loading="busy" @click="publishDraft">
              发布并评测
            </el-button>
          </div>
        </header>
        <p v-if="draft" class="draft-meta">
          {{ draft.name }} · {{ draftItems.length }} 条 · 发布后自动生成下一份草稿
        </p>
        <el-table v-if="draftItems.length" :data="draftItems" size="small" height="360">
          <el-table-column label="问题" min-width="180">
            <template #default="{ row }"><span class="cell-ellipsis" :title="row.question_text">{{ row.question_text }}</span></template>
          </el-table-column>
          <el-table-column label="期望 SQL" min-width="200">
            <template #default="{ row }"><code class="cell-code cell-ellipsis" :title="row.expected_sql">{{ row.expected_sql }}</code></template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click="openEditDialog(row.id)">编辑</el-button>
              <el-button size="small" link type="danger" @click="removeItem(row.id)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="草稿暂无条目；请在“数据回流”中采纳候选，或直接添加条目" :image-size="72" />
      </section>

      <section class="panel-card">
        <h3>评测集版本（发布后不可修改）</h3>
        <el-table v-if="publishedDatasets.length" :data="publishedDatasets" size="small" height="200" @row-click="openDataset">
          <el-table-column label="版本" width="80">
            <template #default="{ row }">v{{ row.version }}</template>
          </el-table-column>
          <el-table-column label="条目" prop="item_count" width="70" align="right" />
          <el-table-column label="发布时间" width="160">
            <template #default="{ row }">{{ (row.published_at ?? '').replace('T', ' ').slice(0, 19) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button size="small" link type="primary" @click.stop="rerun(row.id)">重跑评测</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else description="还没有发布过评测集" :image-size="72" />
        <el-divider />
        <h4 class="section-subtitle">选中版本的冻结内容</h4>
        <template v-if="selectedDataset">
          <p class="draft-meta">v{{ selectedDataset.version }} · {{ selectedDataset.items.length }} 条</p>
          <el-table :data="selectedDataset.items" size="small" height="220">
            <el-table-column label="问题" min-width="180">
              <template #default="{ row }"><span class="cell-ellipsis" :title="row.question_text">{{ row.question_text }}</span></template>
            </el-table-column>
            <el-table-column label="期望 SQL" min-width="200">
              <template #default="{ row }"><code class="cell-code cell-ellipsis" :title="row.expected_sql">{{ row.expected_sql }}</code></template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else description="点击上方版本行查看当时的评测样本" :image-size="60" />
      </section>
    </div>

    <section class="panel-card">
      <header class="panel-card-head">
        <h3>评测运行记录</h3>
        <el-button size="small" @click="() => loadRuns().then(scheduleRunsPolling)">刷新</el-button>
      </header>
      <el-table v-if="runs.length" :data="runs" size="small" @row-click="openRun">
        <el-table-column label="运行" width="80">
          <template #default="{ row }">#{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="版本" width="70">
          <template #default="{ row }">v{{ row.dataset_version }}</template>
        </el-table-column>
        <el-table-column label="触发" width="110">
          <template #default="{ row }">{{ row.trigger_type === 'AUTO_PUBLISH' ? '发布自动' : '手动重跑' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="row.status === 'SUCCESS' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
              {{ row.status === 'SUCCESS' ? '已完成' : row.status === 'FAILED' ? '失败' : row.status === 'RUNNING' ? '评测中' : '排队中' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="160">
          <template #default="{ row }">{{ row.finished_items }} / {{ row.total_items }} 条 · 通过 {{ row.passed_items }}</template>
        </el-table-column>
        <el-table-column label="开始时间" width="170">
          <template #default="{ row }">{{ (row.started_at ?? '').replace('T', ' ').slice(0, 19) || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="还没有评测运行" :image-size="72" />
    </section>

    <el-dialog v-model="itemDialogVisible" :title="itemForm.id ? '编辑草稿条目' : '添加草稿条目'" width="640">
      <el-form label-position="top">
        <el-form-item label="问题原文（评测输入）">
          <el-input v-model="itemForm.question" type="textarea" :rows="2" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="期望 SQL（评测金标）">
          <el-input v-model="itemForm.sql" type="textarea" :rows="6" class="sql-input" maxlength="30000" />
        </el-form-item>
        <el-form-item label="备注（可选）">
          <el-input v-model="itemForm.note" type="textarea" :rows="2" maxlength="1000" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="itemDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submitItem">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" :title="runDetail ? `评测报告 #${runDetail.id}（v${runDetail.dataset_version}）` : '评测报告'" size="62%">
      <template v-if="runDetail">
        <div class="metric-grid">
          <div class="metric-card">
            <span>综合通过率</span><strong>{{ runDetail.summary.pass_rate }}%</strong>
            <small>{{ runDetail.summary.passed_items }} / {{ runDetail.summary.total_items }} 条全部维度通过</small>
          </div>
          <div class="metric-card">
            <span>执行成功率</span><strong>{{ runDetail.summary.execution_success_rate }}%</strong>
            <small>{{ runDetail.summary.execution_success_items }} 条成功执行</small>
          </div>
          <div class="metric-card">
            <span>SQL 匹配率</span><strong>{{ runDetail.summary.sql_match_rate }}%</strong>
            <small>与金标规范化一致 {{ runDetail.summary.sql_match_items }} 条</small>
          </div>
          <div class="metric-card">
            <span>结果一致率</span><strong>{{ runDetail.summary.result_consistent_rate }}%</strong>
            <small>行数与内容一致 {{ runDetail.summary.result_consistent_items }} 条</small>
          </div>
          <div class="metric-card">
            <span>耗时 P50 / P95</span>
            <strong>{{ runDetail.summary.p50_elapsed_ms }} / {{ runDetail.summary.p95_elapsed_ms }} ms</strong>
            <small>平均 {{ runDetail.summary.avg_elapsed_ms }} ms</small>
          </div>
        </div>
        <el-alert v-if="runDetail.error_message" type="error" :title="`运行中断：${runDetail.error_message}`" :closable="false" show-icon />
        <el-table :data="runDetail.items" size="small" class="run-items">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="run-item-detail">
                <p><strong>期望 SQL：</strong></p><code class="cell-code">{{ row.expected_sql }}</code>
                <p><strong>生成 SQL：</strong></p><code class="cell-code">{{ row.generated_sql ?? '未生成' }}</code>
                <p v-if="row.error_message"><strong>失败信息：</strong>{{ row.error_message }}</p>
                <p><strong>行数对比：</strong>期望 {{ row.expected_rows ?? '—' }} / 实际 {{ row.actual_rows ?? '—' }} · 耗时 {{ row.elapsed_ms ?? '—' }} ms</p>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="问题" min-width="180">
            <template #default="{ row }"><span class="cell-ellipsis" :title="row.question_text">{{ row.question_text }}</span></template>
          </el-table-column>
          <el-table-column label="执行" width="60" align="center">
            <template #default="{ row }">{{ dimensionMark(row.execution_success) }}</template>
          </el-table-column>
          <el-table-column label="SQL" width="60" align="center">
            <template #default="{ row }">{{ dimensionMark(row.sql_match) }}</template>
          </el-table-column>
          <el-table-column label="结果" width="60" align="center">
            <template #default="{ row }">{{ dimensionMark(row.result_consistent) }}</template>
          </el-table-column>
          <el-table-column label="结论" width="120">
            <template #default="{ row }">
              <el-tag :type="row.outcome === 'PASSED' ? 'success' : 'warning'" size="small" effect="light">
                {{ OUTCOME_LABELS[row.outcome] ?? row.outcome }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>
    </el-drawer>
  </div>
</template>
