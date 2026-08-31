<script setup lang="ts">
import { computed } from 'vue'
import { Check, Close, Loading } from '@element-plus/icons-vue'

/** 一次查询内已到达的阶段（按首次到达时间排序，key 为后端状态名）。 */
export interface AgentStep { key: string; label: string; at?: string }
const props = defineProps<{ steps: AgentStep[]; running: boolean; failed: boolean; waiting: boolean }>()

const finished = computed(() => !props.running && !props.waiting && props.steps.length > 0)
const elapsed = computed(() => {
  const first = props.steps[0]?.at
  const last = props.steps[props.steps.length - 1]?.at
  if (!first || !last) return ''
  const ms = new Date(last).getTime() - new Date(first).getTime()
  if (!Number.isFinite(ms) || ms < 0) return ''
  return ms >= 60_000 ? `${Math.round(ms / 6_000) / 10} 分钟` : `${Math.round(ms / 100) / 10} 秒`
})
</script>

<template>
  <!-- 运行中：完整步骤流，当前步骤高亮；等待用户或已结束时折叠为一行摘要。 -->
  <ol v-if="running || (waiting && steps.length)" class="agent-steps is-active" aria-label="查询执行步骤">
    <li v-for="(step, index) in steps" :key="step.key + index"
        :class="{ 'is-current': index === steps.length - 1 && running, 'is-waiting': index === steps.length - 1 && waiting }">
      <span class="agent-step-icon">
        <Check v-if="index < steps.length - 1" />
        <Loading v-else-if="running" class="spinning" />
        <Loading v-else class="spinning" />
      </span>
      <span class="agent-step-label">{{ step.label }}</span>
      <time v-if="step.at" :datetime="step.at">{{ new Date(step.at).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) }}</time>
    </li>
  </ol>
  <details v-else-if="finished" class="agent-steps-done">
    <summary>{{ failed ? '执行中断，共经过' : '已完成' }} {{ steps.length }} 个步骤<template v-if="elapsed"> · 用时 {{ elapsed }}</template></summary>
    <ol class="agent-steps is-history">
      <li v-for="(step, index) in steps" :key="step.key + index">
        <span class="agent-step-icon"><Check v-if="!failed || index < steps.length - 1" /><Close v-else /></span>
        <span class="agent-step-label">{{ step.label }}</span>
        <time v-if="step.at" :datetime="step.at">{{ new Date(step.at).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) }}</time>
      </li>
    </ol>
  </details>
</template>
