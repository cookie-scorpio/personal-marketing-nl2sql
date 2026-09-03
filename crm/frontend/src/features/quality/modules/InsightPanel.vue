<script setup lang="ts">
/** 优化洞察页：失败热点矩阵、错误聚类、澄清热点、修复案例与模型调用成本，为提示词与系统持续优化提供线索。 */
import { computed, ref, watch } from 'vue'
import { fetchInsight } from '../api'
import { usePolling } from '../usePolling'
import { intentLabel, statusLabel } from '../eventLabels'

const REFRESH_MS = 30000
const windowHours = ref(168)
const { data, error, refresh } = usePolling(() => fetchInsight(windowHours.value), REFRESH_MS)
watch(windowHours, () => void refresh())

const matrix = computed(() => data.value?.failure_matrix ?? [])
const errorTop = computed(() => data.value?.error_top ?? [])
const clarificationCases = computed(() => data.value?.clarification_cases ?? [])
const repairCases = computed(() => data.value?.repair_cases ?? [])
const cost = computed(() => data.value?.model_cost)

const repairStatus = computed(() => {
  const counts = data.value?.repair_status_counts ?? []
  const by = (key: string) => counts.find(row => row.group_key === key)?.group_count ?? 0
  const started = by('STARTED')
  const applied = by('APPLIED')
  return { started, applied, rejected: by('REJECTED'), modelFailed: by('MODEL_FAILED'),
    successRate: started === 0 ? null : Math.round((applied / started) * 100) }
})

const occurredAt = (value: string) => (value ?? '').replace('T', ' ').slice(0, 19)
const REPAIR_STATUS_LABELS: Record<string, string> = {
  STARTED: '修复启动', GENERATED: '产出候选', APPLIED: '修复生效', REJECTED: '候选被拒', MODEL_FAILED: '模型失败',
}
const TRIGGER_LABELS: Record<string, string> = {
  VALIDATION: '校验失败触发', EXECUTION: '执行报错触发', RESULT_REVIEW: '结果复核触发',
}
const formatTokens = (value: number | undefined) => (value ?? 0).toLocaleString()
</script>

<template>
  <div class="quality-body">
    <el-alert v-if="error" type="error" :title="`优化洞察数据加载失败：${error}`" :closable="false" show-icon />
    <div class="quality-toolbar">
      <el-radio-group v-model="windowHours" size="small">
        <el-radio-button :value="24">近 24 小时</el-radio-button>
        <el-radio-button :value="168">近 7 天</el-radio-button>
        <el-radio-button :value="720">近 30 天</el-radio-button>
      </el-radio-group>
      <small class="quality-toolbar-hint">失败热点按意图×终态定位提示词与生成链路薄弱点；修复/澄清案例用于优化评估。</small>
    </div>
    <template v-if="data">
      <div class="metric-grid">
        <div class="metric-card" :class="matrix.length ? 'is-warn' : 'is-ok'">
          <span>失败/降级任务</span>
          <strong>{{ matrix.reduce((sum, row) => sum + row.total_count, 0).toLocaleString() }}</strong>
          <small>失败 {{ matrix.reduce((sum, row) => sum + row.failed_count, 0) }} · 超时 {{ matrix.reduce((sum, row) => sum + row.timed_out_count, 0) }} · 降级 {{ matrix.reduce((sum, row) => sum + row.degraded_count, 0) }}</small>
        </div>
        <div class="metric-card" :class="repairStatus.started ? 'is-warn' : 'is-ok'">
          <span>SQL 修复</span>
          <strong>{{ repairStatus.started.toLocaleString() }}</strong>
          <small>生效 {{ repairStatus.applied }} 次 · 成功率 {{ repairStatus.successRate === null ? '—' : repairStatus.successRate + '%' }}</small>
        </div>
        <div class="metric-card">
          <span>模型调用</span>
          <strong>{{ formatTokens(cost?.total_calls) }}</strong>
          <small>失败 {{ formatTokens(cost?.failed_calls) }} 次 · 均耗时 {{ Math.round(cost?.avg_elapsed_ms ?? 0) }} ms</small>
        </div>
        <div class="metric-card">
          <span>Token 消耗</span>
          <strong>{{ formatTokens(cost?.total_tokens) }}</strong>
          <small>输入 {{ formatTokens(cost?.prompt_tokens) }} · 输出 {{ formatTokens(cost?.completion_tokens) }}</small>
        </div>
      </div>

      <div class="panel-grid">
        <section class="panel-card">
          <h3>失败热点矩阵（意图 × 终态）</h3>
          <el-table v-if="matrix.length" :data="matrix" size="small" height="240">
            <el-table-column label="意图" min-width="120">
              <template #default="{ row }">{{ intentLabel(row.group_key) || row.group_key }}</template>
            </el-table-column>
            <el-table-column label="失败" prop="failed_count" width="80" align="right" />
            <el-table-column label="超时" prop="timed_out_count" width="80" align="right" />
            <el-table-column label="降级" prop="degraded_count" width="80" align="right" />
            <el-table-column label="合计" prop="total_count" width="80" align="right" />
          </el-table>
          <el-empty v-else description="窗口内没有失败或降级任务" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>错误类型 Top N</h3>
          <el-table v-if="errorTop.length" :data="errorTop" size="small" height="240">
            <el-table-column label="错误摘要" min-width="220">
              <template #default="{ row }"><span class="cell-ellipsis" :title="row.group_key">{{ row.group_key }}</span></template>
            </el-table-column>
            <el-table-column label="次数" prop="group_count" width="80" align="right" />
          </el-table>
          <el-empty v-else description="窗口内没有错误事实" :image-size="72" />
        </section>
      </div>

      <div class="panel-grid">
        <section class="panel-card">
          <h3>澄清热点任务</h3>
          <el-table v-if="clarificationCases.length" :data="clarificationCases" size="small" height="240">
            <el-table-column label="问题原文" min-width="220">
              <template #default="{ row }"><span class="cell-ellipsis" :title="row.query_text">{{ row.query_text }}</span></template>
            </el-table-column>
            <el-table-column label="澄清轮次" prop="clarification_round" width="90" align="right" />
            <el-table-column label="终态" width="90">
              <template #default="{ row }">{{ statusLabel(row.status_code) || row.status_code }}</template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ occurredAt(row.created_at) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="窗口内没有触发澄清的任务" :image-size="72" />
        </section>
        <section class="panel-card">
          <h3>SQL 修复案例</h3>
          <el-table v-if="repairCases.length" :data="repairCases" size="small" height="240">
            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="run-item-detail">
                  <p><strong>失败原因：</strong>{{ row.failure_reason || '—' }}</p>
                  <p><strong>修复说明：</strong>{{ row.repair_reason || '—' }}</p>
                  <p><strong>修复前 SQL：</strong></p><code class="cell-code">{{ row.original_sql || '—' }}</code>
                  <p><strong>修复后 SQL：</strong></p><code class="cell-code">{{ row.repaired_sql || '—' }}</code>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="触发" width="120">
              <template #default="{ row }">{{ TRIGGER_LABELS[row.trigger_phase] ?? row.trigger_phase }}</template>
            </el-table-column>
            <el-table-column label="失败原因" min-width="180">
              <template #default="{ row }"><span class="cell-ellipsis" :title="row.failure_reason ?? ''">{{ row.failure_reason || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="结论" width="90">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status_code === 'APPLIED' ? 'success' : 'warning'" effect="light">
                  {{ REPAIR_STATUS_LABELS[row.status_code] ?? row.status_code }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="时间" width="150">
              <template #default="{ row }">{{ occurredAt(row.created_at) }}</template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="窗口内没有触发 SQL 修复" :image-size="72" />
        </section>
      </div>
    </template>
  </div>
</template>
