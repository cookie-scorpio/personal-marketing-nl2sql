<script setup lang="ts">
/**
 * 质量审计后台的容器页：按侧栏选中的模块切换内容页面。
 * 各模块自行拉取数据并维护轮询，容器只负责标题与挂载。
 */
import type { QualityModule } from './types'
import OverviewPanel from './modules/OverviewPanel.vue'
import SystemHealthPanel from './modules/SystemHealthPanel.vue'
import ResourceMonitorPanel from './modules/ResourceMonitorPanel.vue'
import SqlHealthPanel from './modules/SqlHealthPanel.vue'
import BusinessMonitorPanel from './modules/BusinessMonitorPanel.vue'
import LogCenterPanel from './modules/LogCenterPanel.vue'
import DataFeedbackPanel from './modules/DataFeedbackPanel.vue'
import EvaluationPanel from './modules/EvaluationPanel.vue'
import InsightPanel from './modules/InsightPanel.vue'

const props = defineProps<{ module: QualityModule }>()
defineEmits<{ navigate: [module: QualityModule] }>()

const titles: Record<QualityModule, { title: string; description: string }> = {
  overview: { title: '总览', description: '系统运行、业务规模与数据回流的核心指标一屏速览。' },
  health: { title: '系统健康', description: '数据库、Redis、模型网关、磁盘与事实补偿链路的实时健康状态。' },
  resources: { title: '资源监控', description: '进程与系统资源占用趋势，含 GPU 探测。' },
  sql: { title: 'SQL 健康度', description: 'SQL 生成、校验、执行各阶段事实与修复轨迹的窗口统计。' },
  business: { title: '业务监控', description: '在册客户规模、问数执行量、成功率与耗时分布。' },
  logs: { title: '日志中心', description: '运行日志、SQL 复核日志、会话日志与大模型调用日志的尾部检索。' },
  feedback: { title: '数据回流', description: '审核落库的候选审计数据，补充金标后采纳入评测集草稿。' },
  evaluation: { title: '评测管理', description: '维护评测集草稿、发布版本并查看历次评测的多维度报告。' },
  insight: { title: '优化洞察', description: '失败热点、错误聚类、澄清与修复案例，为提示词与系统持续优化提供线索。' },
}
</script>

<template>
  <section class="quality-console" aria-labelledby="quality-console-title">
    <header class="quality-console-head">
      <p class="section-kicker">质量审计后台</p>
      <h2 id="quality-console-title">{{ titles[props.module].title }}</h2>
      <p>{{ titles[props.module].description }}</p>
    </header>
    <OverviewPanel v-if="props.module === 'overview'" @navigate="key => $emit('navigate', key)" />
    <SystemHealthPanel v-else-if="props.module === 'health'" />
    <ResourceMonitorPanel v-else-if="props.module === 'resources'" />
    <SqlHealthPanel v-else-if="props.module === 'sql'" />
    <BusinessMonitorPanel v-else-if="props.module === 'business'" />
    <LogCenterPanel v-else-if="props.module === 'logs'" />
    <DataFeedbackPanel v-else-if="props.module === 'feedback'" />
    <EvaluationPanel v-else-if="props.module === 'evaluation'" />
    <InsightPanel v-else />
  </section>
</template>
