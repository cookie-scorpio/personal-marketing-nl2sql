<script setup lang="ts">
/** 日志中心页：三类日志文件的尾部检索，支持行数、关键字与自动刷新开关。 */
import { computed, ref, watch } from 'vue'
import { Refresh } from '@element-plus/icons-vue'
import { fetchLog, fetchLogCatalog } from '../api'
import { usePolling } from '../usePolling'

const REFRESH_MS = 5000
const CATALOG_REFRESH_MS = 60000
const fileKey = ref('application')
const tailLines = ref(200)
const keyword = ref('')
const autoRefresh = ref(false)

// 自动刷新关闭时轮询停用，参数变化或手动刷新仍会即时拉取。
const { data, error, loading, refresh } = usePolling(
  () => fetchLog(fileKey.value, tailLines.value, keyword.value),
  REFRESH_MS,
  autoRefresh,
)
watch([fileKey, tailLines], () => void refresh(true))
// 关键字输入防抖后检索，避免每个字符都打一次后端。
let keywordTimer: number | undefined
watch(keyword, () => {
  window.clearTimeout(keywordTimer)
  keywordTimer = window.setTimeout(() => void refresh(true), 400)
})
watch(autoRefresh, enabled => { if (enabled) void refresh(true) })

const catalogLoader = usePolling(() => fetchLogCatalog(), CATALOG_REFRESH_MS)
const fileMeta = computed(() => catalogLoader.data.value?.find(item => item.key === fileKey.value))
const sizeLabel = computed(() => {
  const bytes = fileMeta.value?.size_bytes ?? 0
  return bytes > 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
})
const lineText = computed(() => (data.value?.lines ?? []).join('\n'))
</script>

<template>
  <div class="quality-body">
    <div class="quality-toolbar log-toolbar">
      <el-tabs v-model="fileKey" class="log-tabs">
        <el-tab-pane v-for="item in catalogLoader.data.value ?? []" :key="item.key" :name="item.key">
          <template #label>
            {{ item.file_name }}
            <el-tag v-if="!item.exists" size="small" type="info">无文件</el-tag>
          </template>
        </el-tab-pane>
      </el-tabs>
      <div class="log-controls">
        <el-select v-model="tailLines" size="small" style="width: 108px">
          <el-option label="100 行" :value="100" />
          <el-option label="200 行" :value="200" />
          <el-option label="500 行" :value="500" />
        </el-select>
        <el-input v-model="keyword" size="small" placeholder="关键字过滤" clearable style="width: 180px" />
        <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" />
        <el-button size="small" :icon="Refresh" :loading="loading" @click="() => refresh(true)">刷新</el-button>
      </div>
    </div>
    <el-alert v-if="error && !data" type="error" :title="`日志加载失败：${error}`" :closable="false" show-icon />
    <section class="panel-card">
      <header class="panel-card-head">
        <h3>{{ data?.file_name ?? '日志' }}</h3>
        <small class="log-meta">
          {{ fileMeta?.exists ? `文件大小 ${sizeLabel}` : '文件尚未生成' }}
          <template v-if="data?.truncated"> · 仅回扫最近 4MB</template>
          <template v-if="data?.keyword"> · 关键字“{{ data.keyword }}”</template>
        </small>
      </header>
      <pre class="log-viewer">{{ lineText || '暂无日志内容' }}</pre>
    </section>
  </div>
</template>
