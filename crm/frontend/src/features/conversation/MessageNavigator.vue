<script setup lang="ts">
import { computed, ref, watch, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { apiRequest } from '../../app/api'
import type { ConversationMessage } from '../../app/types'

const props = defineProps<{ messages: ConversationMessage[]; sessionId: string; host?: HTMLElement; hasMore: boolean; loadOlder: () => Promise<boolean> }>()
type Anchor = { message_id: number | string; content: string; created_at?: string }
const anchors = ref<Anchor[]>([])
const more = ref(false), loading = ref(false), jumping = ref(false), current = ref('')
const loadFailed = ref(false)
let controller: AbortController | null = null
const items = computed(() => {
  const result = new Map<string, Anchor>()
  anchors.value.forEach(row => result.set(String(row.message_id), row))
  props.messages.filter(m => m.role_code === 'USER').forEach(row => result.set(String(row.message_id), row))
  const time = (row: Anchor) => row.created_at ? new Date(row.created_at).getTime() || 0 : 0
  return [...result.values()].sort((a, b) => time(a) - time(b) || Number(a.message_id) - Number(b.message_id))
})
async function load(moreRows = false) {
  if (loading.value) return
  const session = props.sessionId
  controller?.abort(); const request = new AbortController(); controller = request; loading.value = true; loadFailed.value = false
  try {
    const after = moreRows ? anchors.value.at(-1)?.message_id || 0 : 0
    const rows = await apiRequest<Anchor[]>(`/api/v1/conversations/${session}/anchors?after_message_id=${after}`, { signal: request.signal })
    if (session !== props.sessionId) return
    anchors.value = moreRows ? [...anchors.value, ...rows] : rows; more.value = rows.length === 100
  } catch {
    if (!request.signal.aborted && session === props.sessionId && props.messages.some(m => typeof m.message_id === 'number')) loadFailed.value = true
  } finally { if (controller === request) loading.value = false }
}
async function jump(id: number | string) {
  if (jumping.value) return
  jumping.value = true; const session = props.sessionId
  try {
    // 消息正文仍沿用原分页接口；不为导航执行任何业务SQL。
    while (!props.messages.some(row => String(row.message_id) === String(id)) && props.hasMore && session === props.sessionId) {
      if (!await props.loadOlder()) break
    }
    if (session !== props.sessionId) return
    const node = document.getElementById(`message-${id}`)
    if (!node) { ElMessage.warning('消息尚未加载，请重试'); return }
    current.value = String(id)
    node.scrollIntoView({ block: 'start', behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
    node.focus({ preventScroll: true })
  } finally { jumping.value = false }
}
function track() {
  const top = props.host?.getBoundingClientRect().top || 0
  const visible = items.value.filter(row => (document.getElementById(`message-${row.message_id}`)?.getBoundingClientRect().top ?? Infinity) <= top + 120)
  current.value = String(visible.at(-1)?.message_id || items.value[0]?.message_id || '')
}
watch(() => props.host, (host, previous) => { previous?.removeEventListener('scroll', track); host?.addEventListener('scroll', track, { passive: true }) })
watch(() => props.sessionId, () => { controller?.abort(); loading.value = false; loadFailed.value = false; anchors.value = []; more.value = false; current.value = ''; if (props.messages.some(m => typeof m.message_id === 'number')) void load() }, { immediate: true })
watch(() => props.messages.length, () => { if (!anchors.value.length && props.messages.some(m => typeof m.message_id === 'number')) void load() })
onUnmounted(() => { controller?.abort(); props.host?.removeEventListener('scroll', track) })
</script>

<template>
  <nav v-if="items.length" class="message-navigator" aria-label="跳转到用户输入">
    <el-tooltip v-for="(item, index) in items" :key="item.message_id" placement="right" :show-after="120" :hide-after="0" popper-class="message-anchor-preview">
      <template #content><strong>第 {{ index + 1 }} 条输入</strong><p>{{ item.content }}</p></template>
      <button type="button" class="message-anchor" :class="{ active: current === String(item.message_id) }" :disabled="jumping" :aria-label="`跳转到：${item.content}`" :aria-current="current === String(item.message_id) ? 'location' : undefined" @click="jump(item.message_id)"><span /></button>
    </el-tooltip>
    <button v-if="more" class="anchor-more" :disabled="loading" aria-label="加载更多消息导航" title="加载更多消息导航" @click="load(true)">···</button>
    <button v-if="loadFailed" class="anchor-more" :disabled="loading" aria-label="重试加载消息导航" title="目录加载失败，点击重试；已显示的消息仍可跳转" @click="load(anchors.length > 0)">↻</button>
  </nav>
</template>
