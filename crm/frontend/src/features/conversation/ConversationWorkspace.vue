<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { ChatDotRound, Promotion, Loading, VideoPause, Refresh } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { apiRequest, apiUrl, ApiError, getToken } from '../../app/api'
import { useAuth } from '../../app/auth'
import type { ConversationMessage, ConversationDetail, TaskStatus, SubmitQueryResponse } from '../../app/types'
import QueryResultView from './QueryResultView.vue'

const emit = defineEmits<{ 'sessions-changed': [] }>()
const { user } = useAuth()
const storageKey = computed(() => `nl2sql-session-${user.value?.user_id}`)
const draft = ref(''), thinking = ref(true), sessionId = ref(crypto.randomUUID() as string)
const messages = ref<ConversationMessage[]>([]), task = ref<TaskStatus | null>(null)
const sending = ref(false), cancelling = ref(false), loading = ref(false)
const hasMore = ref(false), connectionNote = ref('')
const selected = ref(''), answer = ref(''), listHost = ref<HTMLElement>()
const phases = ref<Record<string, string[]>>({})
const failedSubmission = ref('')
const composer = ref<HTMLTextAreaElement>()
const navigationBusy = computed(() => loading.value || sending.value || cancelling.value || !!failedSubmission.value)
type Pending = { key: string; text: string; session: string; thinking: boolean }
let pending: Pending | null = null
let controller: AbortController | null = null
let generation = 0
let cancelRequested = false
let destroyed = false
const stopped = new Set(['SUCCESS', 'FAILED', 'CANCELLED', 'TIMED_OUT', 'DEGRADED'])
const waiting = new Set(['ASKING', 'CONFIRMING'])
const active = computed(() => !!task.value?.cancellable)
const running = computed(() => sending.value || (active.value && !waiting.has(task.value!.status)))
const canSend = computed(() => !!draft.value.trim() && !navigationBusy.value && (!active.value || task.value?.status === 'ASKING'))
const examples = ['帮我查找一下李先生的资产信息', '统计近30天各机构客户交易金额', '按月展示今年客户交易金额趋势', '比较本季度不同渠道的营销转化率']
const phaseLabels: Record<string, string> = { RECEIVED: '请求已接收', INTENT_ANALYZING: '理解问题与确认查询对象', SQL_GENERATING: '生成查询计划', VALIDATING: '校验 SQL 与数据权限', EXECUTING: '执行 MySQL 查询', REPAIRING: '修复查询 SQL', FALLING_BACK: '匹配受控模板', PACKAGING: '整理图表与分析', SUCCESS: '查询完成', ASKING: '等待补充', CONFIRMING: '等待执行确认', FAILED: '查询未完成', CANCELLED: '查询已取消', TIMED_OUT: 'SQL 执行超时', DEGRADED: '降级处理结束' }

function disconnect() { generation++; controller?.abort(); controller = null }
async function scroll(force = false) { await nextTick(); const host = listHost.value; if (host && (force || host.scrollHeight - host.scrollTop - host.clientHeight < 240)) host.scrollTo({ top: host.scrollHeight, behavior: 'auto' }) }
function assistant(status: TaskStatus) {
  const key = `assistant-${status.task_id}-${status.clarification_round}`
  let message = messages.value.find(m => m.role_code === 'ASSISTANT' && m.task_id === status.task_id && m.payload?.clarification_round === status.clarification_round)
  if (!message) { message = { message_id: key, task_id: status.task_id, role_code: 'ASSISTANT', content: status.message }; messages.value.push(message); message = messages.value[messages.value.length - 1]! }
  if (!message.payload || status.state_version >= message.payload.state_version) { message.payload = status; message.content = status.message }
}
function update(status: TaskStatus) {
  const previous = task.value
  assistant(status)
  const history = phases.value[status.task_id] ||= []
  const label = phaseLabels[status.status] || status.message
  if (history[history.length - 1] !== label) history.push(label)
  if (!task.value || status.task_id === task.value.task_id && status.state_version >= task.value.state_version) {
    const oldQuestion = task.value?.question?.question_id
    task.value = status
    if (oldQuestion !== status.question?.question_id) { answer.value = ''; selected.value = '' }
  }
  if (previous?.task_id !== status.task_id || previous.status !== status.status && (stopped.has(status.status) || waiting.has(status.status))) emit('sessions-changed')
  scroll()
}

/** fetch读取SSE以携带JWT；事件编号按任务保存，重连不会重复追加消息。 */
async function subscribe(id: string) {
  disconnect(); const epoch = generation
  controller = new AbortController(); const signal = controller.signal
  let after = Number(sessionStorage.getItem(`nl2sql-event-${user.value?.user_id}-${id}`) || 0)
  let failures = 0
  while (!signal.aborted && epoch === generation) {
    try {
      const headers = new Headers({ Authorization: `Bearer ${getToken()}`, 'Last-Event-ID': String(after) })
      const response = await fetch(apiUrl(`/api/v1/queries/${id}/events`), { headers, signal })
      if (!response.ok || !response.body) throw new Error('事件连接不可用')
      const reader = response.body.getReader(), decoder = new TextDecoder()
      let buffer = ''
      connectionNote.value = ''
      while (!signal.aborted) {
        const { value, done } = await reader.read()
        if (done) break
        buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')
        let end: number
        while ((end = buffer.indexOf('\n\n')) >= 0) {
          const event = buffer.slice(0, end); buffer = buffer.slice(end + 2)
          const data = event.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
          if (!data) continue
          const status = JSON.parse(data) as TaskStatus
          if (epoch !== generation || signal.aborted) return
          update(status); failures = 0
          const eventId = event.split('\n').find(line => line.startsWith('id:'))?.slice(3).trim()
          if (eventId && Number(eventId) > after) { after = Number(eventId); sessionStorage.setItem(`nl2sql-event-${user.value?.user_id}-${id}`, String(after)) }
        }
      }
      if (task.value && (stopped.has(task.value.status) || waiting.has(task.value.status))) return
    } catch { if (signal.aborted || epoch !== generation) return }
    failures++
    connectionNote.value = failures < 3 ? '进度连接中断，正在恢复；后台任务不会因此取消。' : '实时连接暂不可用，正在通过状态接口恢复进度。'
    try {
      const status = await apiRequest<TaskStatus>(`/api/v1/queries/${id}/status`, { signal })
      if (epoch !== generation) return
      update(status)
      if (stopped.has(status.status) || waiting.has(status.status)) { connectionNote.value = ''; return }
    } catch (error) {
      if (error instanceof ApiError && error.code === 401001) { connectionNote.value = '登录已失效，请重新登录后恢复会话。'; return }
    }
    await new Promise(resolve => window.setTimeout(resolve, Math.min(5000, failures * 1000)))
  }
}

function userMessage(text: string, id = '') { messages.value.push({ message_id: crypto.randomUUID(), task_id: id, role_code: 'USER', content: text }); scroll(true) }
function inlineError(text: string, id = '') { messages.value.push({ message_id: crypto.randomUUID(), task_id: id, role_code: 'ASSISTANT', content: text }); scroll(true) }
function errorText(error: unknown) { return error instanceof ApiError ? error.message : '请求未完成，请稍后重试。' }
async function send() {
  if (!canSend.value) return
  if (task.value?.status === 'ASKING') { const text = draft.value.trim(); draft.value = ''; await clarify(text); return }
  const text = draft.value.trim(); draft.value = ''; task.value = null; cancelRequested = false
  pending = { key: crypto.randomUUID(), text, session: sessionId.value, thinking: thinking.value }
  sessionStorage.setItem(`${storageKey.value}-pending`, JSON.stringify(pending))
  localStorage.setItem(storageKey.value, sessionId.value)
  userMessage(text)
  await submitPending()
}
async function submitPending() {
  if (!pending || sending.value) return
  sending.value = true; failedSubmission.value = ''
  const operation = pending
  let accepted = false
  try {
    const result = await apiRequest<SubmitQueryResponse>('/api/v1/queries', { method: 'POST', headers: { 'Idempotency-Key': operation.key }, body: JSON.stringify({ session_id: operation.session, query_text: operation.text, thinking_enabled: operation.thinking, preferred_display: 'AUTO' }) })
    accepted = true
    emit('sessions-changed')
    if (destroyed || sessionId.value !== operation.session) return
    update(await apiRequest<TaskStatus>(`/api/v1/queries/${result.task_id}/status`))
    pending = null; sessionStorage.removeItem(`${storageKey.value}-pending`)
    if (cancelRequested) await cancel()
    else void subscribe(result.task_id)
  } catch (error) {
    // 只有提交接口明确拒绝时才释放幂等键；POST成功后读取状态失败仍须保留原键。
    if (!accepted && error instanceof ApiError && error.code >= 400000 && error.code < 500000) {
      pending = null; sessionStorage.removeItem(`${storageKey.value}-pending`)
      draft.value = operation.text; cancelRequested = false
      inlineError(`提交未被接受：${errorText(error)}。问题已放回输入框，请调整后重试。`)
    } else failedSubmission.value = `提交结果尚未确认：${errorText(error)}。请重试本次提交，系统将使用同一幂等键避免重复执行。`
  } finally { sending.value = false }
}
async function cancel() {
  if (cancelling.value || loading.value) return
  if (!task.value) { cancelRequested = true; connectionNote.value = '已请求停止，取得任务编号后将立即取消。'; return }
  cancelling.value = true
  try { const status = await apiRequest<TaskStatus>(`/api/v1/queries/${task.value.task_id}/cancel`, { method: 'POST' }); disconnect(); update(status); connectionNote.value = '' }
  catch (error) { inlineError(`取消未成功：${errorText(error)} 后台任务可能仍在执行，请重试取消。`, task.value.task_id) }
  finally { cancelling.value = false }
}
async function clarify(text = answer.value.trim() || selected.value) {
  const current = task.value
  if (!current?.question || !text || navigationBusy.value) return
  sending.value = true; userMessage(text, current.task_id)
  try {
    await apiRequest(`/api/v1/conversations/${sessionId.value}/messages`, { method: 'POST', body: JSON.stringify({ task_id: current.task_id, question_id: current.question.question_id, answer_text: text }) })
    answer.value = ''; selected.value = ''
    update(await apiRequest<TaskStatus>(`/api/v1/queries/${current.task_id}/status`)); void subscribe(current.task_id)
  } catch (error) { inlineError(errorText(error), current.task_id); await refreshTask() }
  finally { sending.value = false }
}
async function decide(decision: 'CONFIRM' | 'REJECT') {
  const current = task.value
  if (!current?.confirmation || navigationBusy.value) return
  if (decision === 'REJECT') { await cancel(); return }
  sending.value = true
  try {
    const status = await apiRequest<TaskStatus>(`/api/v1/queries/${current.task_id}/confirmations`, { method: 'POST', body: JSON.stringify({ confirm_token: current.confirmation.confirm_token, decision }) })
    userMessage('确认并执行', current.task_id); update(status); void subscribe(current.task_id)
  } catch (error) { inlineError(errorText(error), current.task_id); await refreshTask() }
  finally { sending.value = false }
}
async function refreshTask() { if (!task.value) return; try { update(await apiRequest<TaskStatus>(`/api/v1/queries/${task.value.task_id}/status`)) } catch { /* 原错误保留在会话中 */ } }
async function openSession(id: string, older = false) {
  if (loading.value || sending.value || cancelling.value) return false
  if (pending && id !== pending.session) { ElMessage.warning('请先确认本次提交结果，再切换会话'); return false }
  loading.value = true
  try {
    const before = older ? Math.min(...messages.value.map(m => Number(m.message_id)).filter(Number.isFinite)) : 0
    const result = await apiRequest<ConversationDetail>(`/api/v1/conversations/${id}?before_message_id=${before || 0}`)
    if (destroyed) return false
    if (!older) disconnect()
    sessionId.value = id; localStorage.setItem(storageKey.value, id)
    messages.value = older ? [...result.messages, ...messages.value] : result.messages
    hasMore.value = result.has_more
    if (!older) {
      task.value = null; draft.value = ''; connectionNote.value = ''; failedSubmission.value = ''
      const last = [...messages.value].reverse().find(m => m.payload)?.payload
      if (last) task.value = last
      if (result.active_task_id) { task.value = await apiRequest<TaskStatus>(`/api/v1/queries/${result.active_task_id}/status`); update(task.value); thinking.value = task.value.thinking_enabled; if (!waiting.has(task.value.status)) void subscribe(result.active_task_id) }
      await scroll(true)
    }
    return true
  } catch (error) {
    if (error instanceof ApiError && error.code === 404001 && !messages.value.length) { localStorage.removeItem(storageKey.value); ElMessage.warning('该会话已不存在，请新建会话') }
    else inlineError(`会话恢复失败：${errorText(error)}`)
    return false
  }
  finally { loading.value = false }
}
function newConversation() {
  if (loading.value || cancelling.value) return false
  if (sending.value || pending) { ElMessage.warning('请先确认本次提交结果，再新建会话'); return false }
  disconnect(); sessionId.value = crypto.randomUUID(); messages.value = []; task.value = null; draft.value = ''; thinking.value = true; phases.value = {}; hasMore.value = false; connectionNote.value = ''; failedSubmission.value = ''
  localStorage.removeItem(storageKey.value)
  void nextTick(() => composer.value?.focus())
  return true
}
function enter(event: KeyboardEvent) { if (event.isComposing || event.shiftKey) return; event.preventDefault(); if (canSend.value) void send() }
defineExpose({ newConversation, openSession, sessionId, navigationBusy })
onMounted(async () => {
  const saved = localStorage.getItem(storageKey.value)
  if (saved) await openSession(saved)
  const raw = sessionStorage.getItem(`${storageKey.value}-pending`)
  if (raw) { try { pending = JSON.parse(raw) as Pending; sessionId.value = pending.session; failedSubmission.value = '上次提交结果尚未确认，请重试原提交；不会重复创建任务。' } catch { sessionStorage.removeItem(`${storageKey.value}-pending`) } }
})
onUnmounted(() => { destroyed = true; disconnect() })
</script>

<template>
  <section class="conversation-card agent-workspace">
    <header class="conversation-head"><div><p class="section-kicker"><ChatDotRound /> 智能查询助手 · v1.2</p><h2>把问题说清，让数据回答</h2><p>支持连续追问；客户身份、数据权限和统计口径由服务端校验。</p></div></header>
    <div ref="listHost" class="chat-messages" :aria-busy="loading">
      <p v-if="loading" class="submission-progress" role="status"><Loading class="spinning" /> 正在加载会话…</p>
      <button v-if="hasMore" class="load-older" :disabled="loading" @click="openSession(sessionId, true)">加载更早的消息</button>
      <div v-if="!messages.length && !loading" class="chat-welcome"><h3>从一个具体的业务问题开始</h3><p>可以查客户、看趋势、比较渠道，也可以在结果后继续追问。</p><div class="question-examples"><button v-for="example in examples" :key="example" type="button" class="question-example" @click="draft = example; composer?.focus()"><span>{{ example }}</span></button></div><p class="chart-reason">当前仅使用虚构客户数据。完整姓名仅用于定位，查询结果统一脱敏。</p></div>
      <article v-for="message in messages" :key="message.message_id" class="chat-message" :class="message.role_code === 'USER' ? 'user-message' : 'assistant-message'">
        <div v-if="message.role_code === 'USER'" class="user-bubble">{{ message.content }}</div>
        <template v-else><div class="assistant-avatar"><ChatDotRound /></div><div class="assistant-body">
          <template v-if="message.payload">
            <p class="query-echo">当前查询内容为：{{ message.payload.display_query || '正在确认查询对象和条件' }}</p>
            <div class="stage-status" :class="{ 'is-error': ['FAILED', 'TIMED_OUT'].includes(message.payload.status) }" role="status"><Loading v-if="message.payload.cancellable && !waiting.has(message.payload.status)" class="spinning" /><span>{{ phaseLabels[message.payload.status] || message.payload.status }}</span><small v-if="message.payload.status === 'INTENT_ANALYZING'">{{ message.payload.thinking_enabled ? '思考模式已开启' : '思考模式已关闭' }}</small></div>
            <details v-if="(phases[message.task_id]?.length || 0) > 1" class="stage-details"><summary>执行阶段</summary><ol><li v-for="(phase, i) in phases[message.task_id]" :key="i">{{ phase }}</li></ol></details>
            <p v-if="!message.payload.result" class="assistant-text">{{ message.payload.error?.message || message.payload.message }}</p>
            <template v-if="message.payload.question && task?.task_id === message.task_id && task.question?.question_id === message.payload.question.question_id && task.status === 'ASKING'">
              <div v-if="task.question.candidates?.length" class="customer-options"><button v-for="candidate in task.question.candidates" :key="candidate.customer_id" :disabled="navigationBusy" @click="clarify(candidate.customer_id)"><strong>{{ candidate.name }} <small>{{ candidate.customer_id }}</small></strong><span>机构 {{ candidate.branch_id }} · {{ candidate.mobile }}</span></button></div>
              <div class="answer-options"><button v-for="option in task.question.options" :key="option" :disabled="navigationBusy" :class="{ selected: selected === option }" @click="selected = option; answer = ''">{{ option }}</button></div>
              <div class="chat-answer"><el-input v-model="answer" maxlength="200" placeholder="补充客户信息或查询条件" @keydown.enter="!$event.isComposing && clarify()" /><el-button type="primary" :loading="sending" :disabled="navigationBusy || (!answer.trim() && !selected)" @click="clarify()">补充后继续</el-button><el-button :loading="cancelling" @click="cancel">取消查询</el-button></div>
            </template>
            <div v-if="message.payload.confirmation && task?.task_id === message.task_id && task.status === 'CONFIRMING' && task.confirmation?.confirm_token === message.payload.confirmation.confirm_token" class="chat-confirm"><ul><li v-for="reason in task.confirmation.reasons" :key="reason">{{ reason }}</li></ul><el-button :disabled="navigationBusy" @click="decide('REJECT')">取消查询</el-button><el-button type="danger" :loading="sending" @click="decide('CONFIRM')">确认并执行</el-button></div>
            <QueryResultView v-if="message.payload.result" :result="message.payload.result" />
            <p v-if="['FAILED','TIMED_OUT','CANCELLED'].includes(message.payload.status)" class="chart-reason">{{ message.payload.status === 'CANCELLED' ? '查询已停止推进，不会继续修复或降级执行。' : '可以调整条件后重新提问。' }} 任务编号：{{ message.task_id }}</p>
          </template><p v-else class="assistant-text inline-error" role="alert">{{ message.content }}</p>
        </div></template>
      </article>
      <div v-if="sending && !active" class="submission-progress" role="status"><Loading class="spinning" /> {{ cancelRequested ? '正在确认任务编号，以便取消…' : '正在提交查询…' }}</div>
      <div v-if="failedSubmission" class="inline-error" role="alert"><p>{{ failedSubmission }}</p><el-button :loading="sending" @click="submitPending"><Refresh /> 重试本次提交</el-button></div>
    </div>
    <div class="chat-composer-wrap"><p v-if="connectionNote" class="connection-note" role="status">{{ connectionNote }}</p><div class="chat-composer"><textarea ref="composer" v-model="draft" :disabled="loading" maxlength="1000" rows="2" :placeholder="task?.status === 'ASKING' ? '补充当前问题所需的信息…' : '输入业务问题，或承接上文继续追问…'" aria-label="业务问题" @keydown.enter="enter" /><button type="button" class="send-button" :class="{ 'stop-button': running || task?.status === 'CONFIRMING' }" :disabled="loading || cancelling || (running ? cancelRequested : task?.status === 'CONFIRMING' ? false : !canSend)" :aria-label="running || task?.status === 'CONFIRMING' ? '停止当前查询' : '发送查询'" @click="running || task?.status === 'CONFIRMING' ? cancel() : send()"><Loading v-if="cancelling" class="spinning" /><VideoPause v-else-if="running || task?.status === 'CONFIRMING'" /><Promotion v-else /></button></div><div class="chat-composer-foot"><label><el-switch v-model="thinking" :disabled="active || navigationBusy" aria-label="思考模式" /><span>思考模式</span><small>默认开启，复杂问题可能需要更长时间</small></label><span>Enter 发送 · Shift+Enter 换行</span></div></div>
  </section>
</template>
