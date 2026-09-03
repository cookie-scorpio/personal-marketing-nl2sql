<script setup lang="ts">
/**
 * 后台管理侧栏的模块入口，替换问数工作区的"新建会话/会话列表"。
 * 点击只切换主区域内容，不触发浏览器跳转，与顶部工作区下拉保持同一交互习惯。
 */
import { DataBoard, CircleCheck, Cpu, DataAnalysis, TrendCharts, Document, Refresh, Histogram, Aim } from '@element-plus/icons-vue'
import type { QualityModule } from './types'

defineProps<{ module: QualityModule }>()
defineEmits<{ navigate: [module: QualityModule] }>()

const entries: Array<{ key: QualityModule; label: string; hint: string; icon: typeof DataBoard }> = [
  { key: 'overview', label: '总览', hint: '核心指标一屏速览', icon: DataBoard },
  { key: 'health', label: '系统健康', hint: '数据库、Redis、模型网关', icon: CircleCheck },
  { key: 'resources', label: '资源监控', hint: 'CPU、内存与 GPU', icon: Cpu },
  { key: 'sql', label: 'SQL 健康度', hint: '生成、修复与失败分类', icon: DataAnalysis },
  { key: 'business', label: '业务监控', hint: '客户数与执行成功率', icon: TrendCharts },
  { key: 'logs', label: '日志中心', hint: '运行、SQL、会话与大模型日志', icon: Document },
  { key: 'feedback', label: '数据回流', hint: '候选审计数据审核入集', icon: Refresh },
  { key: 'evaluation', label: '评测管理', hint: '评测集发布与评测报告', icon: Histogram },
  { key: 'insight', label: '优化洞察', hint: '失败热点与优化线索', icon: Aim },
]
</script>

<template>
  <nav class="quality-nav" aria-label="后台管理模块">
    <button
      v-for="entry in entries"
      :key="entry.key"
      type="button"
      class="quality-nav-item"
      :class="{ 'is-active': module === entry.key }"
      :aria-current="module === entry.key ? 'page' : undefined"
      @click="$emit('navigate', entry.key)"
    >
      <el-icon :size="18"><component :is="entry.icon" /></el-icon>
      <span class="quality-nav-text"><strong>{{ entry.label }}</strong><small>{{ entry.hint }}</small></span>
    </button>
  </nav>
</template>
