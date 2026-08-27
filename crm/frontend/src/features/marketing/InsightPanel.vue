<script setup lang="ts">
import { CircleCheckFilled, Connection, DataLine, MagicStick, TrendCharts } from '@element-plus/icons-vue'
const activities = [
  { title: '规则快速路径', progress: 100, target: '高频场景', detail: '无需调用模型' },
  { title: 'DeepSeek 智能路径', progress: 100, target: '自由提问', detail: '结构化生成 SQL' },
]
</script>

<template>
  <aside class="insight-panel">
    <section class="side-card opportunity-card">
      <div class="side-card-head"><div><span class="side-icon red"><MagicStick /></span><h3>提问建议</h3></div><span class="count-tag">V1.1</span></div>
      <p class="guide-copy">说明对象、指标、时间和期望维度，通常能一次得到更准确的结果。</p>
      <div class="opportunity-list">
        <div><span class="list-icon amber"><TrendCharts /></span><span><strong>趋势分析</strong><small>近6个月各月交易金额变化</small></span></div>
        <div><span class="list-icon violet"><DataLine /></span><span><strong>分布分析</strong><small>各年龄段客户数量与资产规模</small></span></div>
      </div>
    </section>
    <section class="side-card">
      <div class="side-card-head"><div><span class="side-icon green"><Connection /></span><h3>混合识别策略</h3></div><span class="count-tag">2条路径</span></div>
      <div class="campaign-list">
        <div v-for="item in activities" :key="item.title" class="campaign-item">
          <div class="campaign-title"><strong>{{ item.title }}</strong><span>{{ item.detail }}</span></div>
          <el-progress :percentage="item.progress" :show-text="false" :stroke-width="6" :aria-label="`${item.title}目标完成度`" />
          <div class="campaign-meta"><span>{{ item.target }}</span><span>统一安全校验</span></div>
        </div>
      </div>
    </section>
    <section class="data-trust-card">
      <div class="trust-title"><CircleCheckFilled /><strong>数据可信说明</strong></div>
      <p>所有结果均来自模拟数据。普通只读查询自动执行，高风险或潜在慢查询会在执行前请求确认。</p>
      <span><Connection /> MySQL 8.4 · Redis 7.x</span>
    </section>
  </aside>
</template>
