<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ChatDotRound, Loading, Refresh } from '@element-plus/icons-vue'
import { apiRequest } from '../../app/api'
import type { ConversationSummary } from '../../app/types'

const props = defineProps<{ activeSessionId?: string; disabled: boolean; refreshVersion: number }>()
defineEmits<{ select: [id: string] }>()
const sessions = ref<ConversationSummary[]>([])
const loading = ref(false), error = ref(''), moreAvailable = ref(false)
const pageSize = 30
let page = 0
let controller: AbortController | null = null

const groups = computed(() => {
  const today = new Date(); today.setHours(0, 0, 0, 0)
  const yesterday = new Date(today); yesterday.setDate(yesterday.getDate() - 1)
  const result: { label: string; sessions: ConversationSummary[] }[] = []
  for (const item of sessions.value) {
    const time = new Date(item.updated_at).getTime()
    const label = time >= today.getTime() ? '今天' : time >= yesterday.getTime() ? '昨天' : '更早'
    let group = result.find(entry => entry.label === label)
    if (!group) { group = { label, sessions: [] }; result.push(group) }
    group.sessions.push(item)
  }
  return result
})

async function load(more = false) {
  if (more && loading.value) return
  controller?.abort()
  const request = new AbortController(); controller = request
  loading.value = true; error.value = ''
  try {
    // 刷新已加载的页，避免后台状态变化时突然丢失用户展开的历史。
    const pages = more ? [page + 1] : Array.from({ length: Math.max(1, page) }, (_, i) => i + 1)
    const rows: ConversationSummary[] = []
    let lastSize = 0, lastPage = 1
    for (const number of pages) {
      const result = await apiRequest<ConversationSummary[]>(`/api/v1/conversations?page_no=${number}&page_size=${pageSize}`, { signal: request.signal })
      if (request.signal.aborted) return
      rows.push(...result); lastSize = result.length; lastPage = number
      if (lastSize < pageSize) break
    }
    const combined = more ? [...sessions.value, ...rows] : rows
    sessions.value = [...new Map(combined.map(item => [item.session_id, item])).values()]
    page = lastPage; moreAvailable.value = lastSize === pageSize
  } catch {
    if (!request.signal.aborted) error.value = more ? '更多会话加载失败，请重试。' : '会话历史加载失败，请重试。'
  } finally {
    if (controller === request) { loading.value = false; controller = null }
  }
}

watch(() => props.refreshVersion, () => { void load() }, { immediate: true })
onUnmounted(() => controller?.abort())
</script>

<template>
  <section class="sidebar-conversations" aria-labelledby="conversation-list-title">
    <header class="sidebar-conversations-head">
      <h2 id="conversation-list-title">会话历史</h2>
      <button type="button" class="sidebar-refresh" :disabled="loading" aria-label="刷新会话历史" title="刷新会话历史" @click="load()">
        <Loading v-if="loading" class="spinning" /><Refresh v-else />
      </button>
    </header>
    <div v-if="error" class="sidebar-list-state sidebar-list-error" role="alert"><span>{{ error }}</span><button type="button" @click="load()">重新加载</button></div>
    <nav class="sidebar-conversation-list" aria-label="会话历史" :aria-busy="loading">
      <p v-if="loading && !sessions.length" class="sidebar-list-state" role="status">正在加载会话…</p>
      <p v-else-if="!sessions.length && !error" class="sidebar-list-state">暂无会话记录<span>发送第一个问题后，会话会保存在这里。</span></p>
      <section v-for="group in groups" :key="group.label" class="sidebar-conversation-group" :aria-label="group.label">
        <h3>{{ group.label }}</h3>
        <button v-for="item in group.sessions" :key="item.session_id" type="button" class="sidebar-session"
                :class="{ active: activeSessionId === item.session_id }" :disabled="disabled"
                :aria-current="activeSessionId === item.session_id ? 'page' : undefined"
                :title="`${item.title} · ${new Date(item.updated_at).toLocaleString('zh-CN')}${item.active_task_id ? ' · 有待处理查询' : ''}`"
                @click="$emit('select', item.session_id)">
          <ChatDotRound /><span>{{ item.title || '未命名会话' }}</span>
          <i v-if="item.active_task_id" class="session-pending" aria-label="有待处理查询" />
        </button>
      </section>
      <button v-if="moreAvailable" type="button" class="sidebar-load-more" :disabled="loading" @click="load(true)">{{ loading ? '正在加载…' : '加载更多会话' }}</button>
    </nav>
  </section>
</template>
