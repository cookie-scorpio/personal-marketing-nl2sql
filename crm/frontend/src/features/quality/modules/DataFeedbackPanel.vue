<script setup lang="ts">
/**
 * 数据回流页：审核 evaluation_candidate 命中的审计事实。
 * 采纳时必须补充期望 SQL 金标，样本进入当前评测集草稿；忽略仅记录结论。
 */
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { acceptCandidate, fetchCandidates, ignoreCandidate } from '../api'
import { usePolling } from '../usePolling'
import type { CandidateItem } from '../types'

const REFRESH_MS = 15000
const PAGE_SIZE = 10
const status = ref('pending')
const pageNo = ref(1)
const acceptVisible = ref(false)
const submitting = ref(false)
const form = ref({ eventId: '', question: '', sql: '', note: '' })

const { data, error, refresh } = usePolling(
  () => fetchCandidates(status.value, pageNo.value, PAGE_SIZE),
  REFRESH_MS,
)
watch(status, () => { pageNo.value = 1; void refresh(true) })
watch(pageNo, () => void refresh(true))

const rows = computed(() => data.value?.items ?? [])
const total = computed(() => data.value?.total ?? 0)
const EVENT_LABELS: Record<string, string> = {
  QUERY_FAILED: '问数失败', QUERY_TIMED_OUT: '问数超时', QUERY_DEGRADED: '降级完成',
  QUERY_SQL_ERROR: 'SQL 报错', QUERY_RESULT_MISMATCH: '结果不一致', QUERY_FALLBACK: '模板兜底',
  MODEL_CALL_FAILED: '模型调用失败', MODEL_RESPONSE_REJECTED: '模型响应被拒', FEEDBACK_CHANGED: '用户差评',
}
const eventType = (key: string) => EVENT_LABELS[key] ?? key

function openAccept(row: CandidateItem) {
  // 后端已把事实载荷中的问题与 SQL 提升到顶层，这里仅做预填，审计员可修正。
  form.value = {
    eventId: row.event_id,
    question: row.question_text ?? '',
    sql: row.sql_text ?? '',
    note: row.error_text ?? '',
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
    })
    acceptVisible.value = false
    ElMessage.success('已采纳进入评测集草稿')
    await refresh(true)
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '采纳失败')
  } finally {
    submitting.value = false
  }
}

async function submitIgnore(row: CandidateItem) {
  try {
    const { value } = await ElMessageBox.prompt('可填写忽略原因（可选）', `忽略候选 ${eventType(row.event_type)}`, {
      confirmButtonText: '忽略',
      cancelButtonText: '取消',
      inputPlaceholder: '忽略原因',
    })
    await ignoreCandidate(row.event_id, value || undefined)
    ElMessage.success('已忽略该候选')
    await refresh(true)
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return
    ElMessage.error(cause instanceof Error ? cause.message : '忽略失败')
  }
}
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`候选数据加载失败：${error}`" :closable="false" show-icon />
    <div class="quality-toolbar">
      <el-radio-group v-model="status" size="small">
        <el-radio-button value="pending">待审核</el-radio-button>
        <el-radio-button value="ACCEPTED">已采纳</el-radio-button>
        <el-radio-button value="IGNORED">已忽略</el-radio-button>
        <el-radio-button value="all">全部</el-radio-button>
      </el-radio-group>
      <small class="quality-toolbar-hint">候选来自失败、超时、差评等自动标记的审计事实；采纳需补充期望 SQL 作为金标。</small>
    </div>

    <section class="panel-card">
      <el-table v-if="rows.length" :data="rows" size="small">
        <el-table-column label="类型" width="120">
          <template #default="{ row }">
            <el-tag size="small" type="warning" effect="light">{{ eventType(row.event_type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="摘要" min-width="220">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="row.event_summary">{{ row.event_summary }}</span>
          </template>
        </el-table-column>
        <el-table-column label="问题原文" min-width="200">
          <template #default="{ row }">
            <span class="cell-ellipsis" :title="row.question_text ?? ''">{{ row.question_text ?? '未提取到' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发生时间" width="170">
          <template #default="{ row }">{{ (row.occurred_at ?? '').replace('T', ' ').slice(0, 19) }}</template>
        </el-table-column>
        <el-table-column label="审核状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.decision === 'ACCEPTED'" size="small" type="success">已采纳</el-tag>
            <el-tag v-else-if="row.decision === 'IGNORED'" size="small" type="info">已忽略</el-tag>
            <el-tag v-else size="small" type="warning">待审核</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <template v-if="!row.decision">
              <el-button size="small" type="primary" link @click="openAccept(row)">采纳</el-button>
              <el-button size="small" link @click="submitIgnore(row)">忽略</el-button>
            </template>
            <small v-else class="review-note">{{ row.review_note || '—' }}</small>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="当前筛选下没有候选数据" :image-size="88" />
      <el-pagination
        v-if="total > PAGE_SIZE"
        v-model:current-page="pageNo"
        layout="prev, pager, next"
        :page-size="PAGE_SIZE"
        :total="total"
        class="quality-pagination"
      />
    </section>

    <el-dialog v-model="acceptVisible" title="采纳候选进入评测集草稿" width="640">
      <el-form label-position="top">
        <el-form-item label="问题原文（评测输入）">
          <el-input v-model="form.question" type="textarea" :rows="2" maxlength="2000" show-word-limit />
        </el-form-item>
        <el-form-item label="期望 SQL（评测金标）">
          <el-input v-model="form.sql" type="textarea" :rows="6" class="sql-input" maxlength="30000" />
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
  </div>
</template>
