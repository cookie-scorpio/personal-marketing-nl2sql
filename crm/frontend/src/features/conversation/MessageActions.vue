<script setup lang="ts">
import { computed, onUnmounted, ref } from 'vue'
import { CopyDocument, EditPen } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { apiRequest, operationKey } from '../../app/api'
import type { ConversationDetail, ConversationMessage } from '../../app/types'
import { messageText } from './messageText'
const props = defineProps<{ message: ConversationMessage; sessionId: string; editDisabled: boolean }>()
const emit = defineEmits<{ edit: [message: ConversationMessage]; feedback: [value: 'LIKE' | 'DISLIKE' | 'NONE'] }>()
const busy = ref(false)
let disposed = false
onUnmounted(() => { disposed = true })
const assistant = computed(() => props.message.role_code === 'ASSISTANT')
const stamp = computed(() => (assistant.value ? props.message.updated_at : undefined) || props.message.created_at)
const date = computed(() => stamp.value ? new Date(stamp.value) : null)
const validDate = computed(() => date.value && Number.isFinite(date.value.getTime()))
const canRate = computed(() => !!props.message.payload && ['ASKING', 'CONFIRMING', 'SUCCESS', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'DEGRADED'].includes(props.message.payload.status))
async function copy() {
  try { await navigator.clipboard.writeText(messageText(props.message)); if (!disposed) ElMessage.success(assistant.value ? '回答已复制' : '文本已复制') }
  catch { if (!disposed) ElMessage.warning('复制失败，请手动选择文本复制') }
}
async function rate(value: 'LIKE' | 'DISLIKE') {
  if (busy.value || !canRate.value) return
  busy.value = true
  const feedback = props.message.feedback === value ? 'NONE' : value
  try {
    let id = props.message.message_id
    // SSE中的即时消息可能还没有持久化编号，先从本人会话找到对应轮次，不用前端伪造编号。
    if (typeof id !== 'number') {
      const detail = await apiRequest<ConversationDetail>(`/api/v1/conversations/${props.sessionId}`)
      if (disposed) return
      const saved = detail.messages.find(m => m.role_code === 'ASSISTANT' && m.task_id === props.message.task_id && m.payload?.clarification_round === props.message.payload?.clarification_round)
      if (!saved || typeof saved.message_id !== 'number') throw new Error('回复尚未同步，请稍后重试')
      id = saved.message_id
    }
    await apiRequest(`/api/v1/conversations/${props.sessionId}/messages/${id}/feedback`, { method: 'POST', headers: { 'Idempotency-Key': operationKey('fb', id, feedback) }, body: JSON.stringify({ feedback }) })
    if (!disposed) { emit('feedback', feedback); ElMessage.success(feedback === 'NONE' ? '已取消评价' : '评价已保存') }
  } catch (error) { if (!disposed) ElMessage.error(error instanceof Error ? error.message : '评价保存失败，请重试') }
  finally { busy.value = false }
}
</script>
<template>
  <div class="message-actions" :class="{ 'user-actions': !assistant }" :aria-label="assistant ? '回答操作' : '提问操作'">
    <time v-if="!assistant && validDate" :datetime="stamp" :title="date!.toLocaleString('zh-CN')">{{ date!.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) }}</time>
    <button type="button" :aria-label="assistant ? '复制回答文本' : '复制提问文本'" :title="assistant ? '复制回答文本' : '复制文本'" @click="copy"><CopyDocument /></button>
    <button v-if="!assistant" type="button" aria-label="编辑并重新发送" :title="editDisabled ? '请先完成或取消当前查询' : '编辑并重新发送'" :disabled="editDisabled" @click="emit('edit', message)"><EditPen /></button>
    <template v-else>
      <button v-for="vote in (['LIKE', 'DISLIKE'] as const)" :key="vote" type="button" :aria-label="vote === 'LIKE' ? '点赞本条回复' : '点踩本条回复'" :title="vote === 'LIKE' ? '点赞，再次点击取消' : '点踩，再次点击取消'" :aria-pressed="message.feedback === vote" :disabled="busy || !canRate" @click="rate(vote)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" :style="vote === 'DISLIKE' ? { transform: 'rotate(180deg)' } : undefined"><path d="M7 10v11H3V10h4Zm0 0 5-8c2 0 3 2 2 5l-1 3h6c2 0 2 2 2 3l-2 7c-.2.7-1 1-2 1H7" /></svg>
      </button>
      <time v-if="validDate" :datetime="stamp" :title="date!.toLocaleString('zh-CN')">{{ date!.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }) }}</time>
    </template>
  </div>
</template>
