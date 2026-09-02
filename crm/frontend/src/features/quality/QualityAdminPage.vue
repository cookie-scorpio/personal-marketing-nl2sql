<script setup lang="ts">
/**
 * 质量审计员的后台管理占位页。
 * 当前迭代只完成身份、入口和权限边界，不在空页面中伪造尚未定义的审计指标或操作。
 */
</script>

<template>
  <section class="role-page role-empty-page" aria-labelledby="quality-admin-title">
    <div class="role-page-head">
      <p class="section-kicker">质量审计员</p>
      <h2 id="quality-admin-title">后台管理</h2>
      <p>质量审计后台的功能将在后续版本开放。</p>
    </div>
    <div class="empty-workspace" role="status">
      <strong>当前暂无可操作内容</strong>
      <span>已按质量审计员身份进入后台管理。</span>
    </div>
  </section>
</template>
