<script setup lang="ts">
import { Calendar, CircleCheckFilled, Connection, DataLine, Opportunity, TrendCharts, Warning } from '@element-plus/icons-vue'
const activities = [
  { title: '财富季理财提升', progress: 72, target: '目标完成 72%', date: '还剩 12 天', people: '3,620 / 5,000人' },
  { title: '代发客户唤醒', progress: 48, target: '目标完成 48%', date: '还剩 20 天', people: '1,216 / 2,500人' },
]
</script>

<template>
  <aside class="insight-panel">
    <section class="side-card opportunity-card">
      <div class="side-card-head"><div><span class="side-icon red"><Opportunity /></span><h3>今日机会</h3></div><span class="count-tag">演示</span></div>
      <div class="opportunity-number">12 <span>条待跟进</span></div><p>预计可触达客户 1,840 人</p>
      <div class="opportunity-list">
        <button type="button"><span class="list-icon amber"><TrendCharts /></span><span><strong>理财到期客户</strong><small>6 条新机会</small></span><b>›</b></button>
        <button type="button"><span class="list-icon violet"><Warning /></span><span><strong>资产下降预警</strong><small>4 条需关注</small></span><b>›</b></button>
      </div>
      <button class="text-link" type="button">查看全部营销机会 →</button>
    </section>
    <section class="side-card">
      <div class="side-card-head"><div><span class="side-icon green"><DataLine /></span><h3>进行中的活动</h3></div><span class="count-tag">2</span></div>
      <div class="campaign-list">
        <div v-for="item in activities" :key="item.title" class="campaign-item">
          <div class="campaign-title"><strong>{{ item.title }}</strong><span><Calendar /> {{ item.date }}</span></div>
          <el-progress :percentage="item.progress" :show-text="false" :stroke-width="6" :aria-label="`${item.title}目标完成度`" />
          <div class="campaign-meta"><span>{{ item.target }}</span><span>{{ item.people }}</span></div>
        </div>
      </div>
      <button class="secondary-full-button" type="button">管理营销活动</button>
    </section>
    <section class="data-trust-card">
      <div class="trust-title"><CircleCheckFilled /><strong>数据可信说明</strong></div>
      <p>查询结果已进行权限校验与敏感字段脱敏。所有操作均留存审计记录。</p>
      <span><Connection /> 当前模型：Mock 适配器</span>
    </section>
  </aside>
</template>
