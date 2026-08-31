<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { Loading, Search } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { apiRequest } from '../../app/api'
import type { ClarificationQuestion } from '../../app/types'

const props = defineProps<{ question: ClarificationQuestion; sessionId: string; busy: boolean }>()
const emit = defineEmits<{ select: [answer: string]; cancel: [] }>()

const isCustomerSearch = computed(() => ['CUSTOMER_SELECTION','CUSTOMER_IDENTITY','CUSTOMER_NOT_FOUND','CUSTOMER_CONFIRM'].includes(props.question.type))
const multiSelect = computed(() => !!props.question.multi_select && props.question.options.length > 0)
const highlight = ref(0)
const checked = ref<Set<string>>(new Set())
const optionEntries = computed(() => props.question.options.map((label, index) => ({ label, index: index + 1, recommended: label === props.question.recommended_option })))
function onKeydown(event: KeyboardEvent) {
  const count = props.question.options.length
  if (!count) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    highlight.value = event.key === 'ArrowDown' ? (highlight.value + 1) % count : (highlight.value - 1 + count) % count
  } else if (event.key === 'Enter' && !multiSelect.value) {
    event.preventDefault()
    const target = props.question.options[highlight.value]
    if (target) emit('select', target)
  } else if (event.key === 'Escape') {
    event.preventDefault(); emit('cancel')
  }
}
function toggleCheck(label: string) {
  const next = new Set(checked.value)
  next.has(label) ? next.delete(label) : next.add(label)
  checked.value = next
}
function submitMulti() {
  if (!checked.value.size) { ElMessage.warning('请先勾选至少一项'); return }
  emit('select', [...checked.value].join('；'))
}
const keyword = ref('')
const items = ref<Array<{ customer_id: string; name: string; branch_id: string; mobile: string }>>([])
const total = ref(0)
const page = ref(1)
const searching = ref(false)
const searchError = ref('')
const searched = ref(false)
let searchEpoch = 0
let searchTimer: ReturnType<typeof setTimeout> | undefined

const fixedConstraint = computed(() => ['固定姓名', '固定客户编号', '固定手机号后四位']
  .some(key => !!props.question.recognized_slots?.[key]))
const allowedFilters = computed(() => new Set((props.question.recognized_slots?.['筛选类型'] || 'CUSTOMER_NAME,CUSTOMER_ID,MOBILE_SUFFIX').split(',')))
const searchPlaceholder = computed(() => {
  const labels: string[] = []
  if (allowedFilters.value.has('CUSTOMER_NAME')) labels.push('姓名片段')
  if (allowedFilters.value.has('CUSTOMER_ID')) labels.push('客户编号')
  if (allowedFilters.value.has('MOBILE_SUFFIX')) labels.push('手机号后四位')
  return `输入${labels.join('、')}自动筛选`
})

async function runSearch(reset: boolean) {
  const kw = keyword.value.trim()
  const epoch = ++searchEpoch
  searching.value = true; searchError.value = ''
  try {
    const result = await apiRequest<{ total: number; page_no: number; items: typeof items.value }>(
      `/api/v1/conversations/${props.sessionId}/customer-search?keyword=${encodeURIComponent(kw)}&page_no=${reset ? 1 : page.value + 1}&page_size=20`)
    if (epoch !== searchEpoch) return
    total.value = result.total
    items.value = reset ? result.items : [...items.value, ...result.items]
    page.value = result.page_no
    searched.value = true
  } catch (error) {
    if (epoch !== searchEpoch) return
    if (reset) { items.value = []; total.value = 0 }
    searchError.value = error instanceof Error ? error.message : '检索失败，请重试'
  } finally { if (epoch === searchEpoch) searching.value = false }
}
function loadMore() { void runSearch(false) }
watch(() => props.question.question_id, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchEpoch++
  keyword.value = ''
  items.value = []; total.value = 0; page.value = 1; searchError.value = ''; searched.value = false
  if (isCustomerSearch.value && fixedConstraint.value) searchTimer = setTimeout(() => void runSearch(true), 0)
}, { immediate: true })
watch(keyword, () => {
  if (searchTimer) clearTimeout(searchTimer)
  searchEpoch++
  items.value = []; total.value = 0; page.value = 1; searchError.value = ''; searched.value = false
  if (!fixedConstraint.value && !keyword.value.trim()) return
  searchTimer = setTimeout(() => void runSearch(true), 280)
})
onBeforeUnmount(() => { if (searchTimer) clearTimeout(searchTimer); searchEpoch++ })

const recommended = computed(() => props.question.recommended_option || null)
function pickOption(option: string) { emit('select', option) }
</script>

<template>
  <!-- v1.5：原问句条件由服务端锁定；输入框始终为空，只提交附加筛选。 -->
  <div class="clarify-panel" role="region" aria-label="补充选择" tabindex="0" @keydown="onKeydown">
    <p v-if="question.prompt && isCustomerSearch" class="clarify-referent">{{ question.prompt }}</p>
    <template v-if="isCustomerSearch">
      <div class="clarify-search-row">
        <el-input v-model="keyword" maxlength="20" clearable :disabled="busy"
                  :placeholder="searchPlaceholder" aria-label="自动筛选客户">
          <template v-if="!keyword" #prefix><Search class="clarify-search-icon" aria-hidden="true" /></template>
        </el-input>
      </div>
      <p v-if="searchError" class="clarify-error">{{ searchError }}</p>
      <p v-if="searching" class="clarify-hint"><Loading class="spinning" /> 正在检索…</p>
      <p v-else-if="total > 0" class="clarify-hint">共 {{ total }} 位匹配客户{{ total > 20 ? '，可输入更具体的关键词缩小范围' : '' }}</p>
      <p v-else-if="searched" class="clarify-hint">没有符合当前筛选条件的客户</p>
      <div v-if="items.length" class="clarify-candidates" role="listbox" aria-label="候选客户">
        <button v-for="candidate in items" :key="candidate.customer_id" type="button" role="option"
                :aria-selected="false" :disabled="busy" class="clarify-candidate" @click="emit('select', candidate.customer_id)">
          <strong>{{ candidate.name }} <small>{{ candidate.customer_id }}</small></strong>
          <span>机构 {{ candidate.branch_id }} · {{ candidate.mobile }}</span>
        </button>
      </div>
      <button v-if="items.length && items.length < total" type="button" class="clarify-more" :disabled="busy || searching" @click="loadMore">加载更多（已显示 {{ items.length }}/{{ total }}）</button>
      <p class="clarify-hint">也可以直接点击“取消查询”结束本次查询。</p>
    </template>
    <template v-else-if="question.options.length">
      <div class="clarify-chips" :role="multiSelect ? 'group' : 'listbox'" aria-label="口径选项">
        <button v-for="(entry, index) in optionEntries" :key="entry.label" type="button"
                :role="multiSelect ? 'checkbox' : 'option'"
                :aria-checked="multiSelect ? checked.has(entry.label) : false"
                :disabled="busy" class="clarify-chip"
                :class="{ recommended: entry.recommended, highlighted: !multiSelect && highlight === index, checked: multiSelect && checked.has(entry.label) }"
                @click="multiSelect ? toggleCheck(entry.label) : pickOption(entry.label)">
          <span v-if="entry.recommended" class="clarify-recommended-badge">推荐</span>
          <span class="clarify-option-index">{{ index + 1 }}.</span>{{ entry.label }}
          <span v-if="multiSelect && checked.has(entry.label)" class="clarify-check-mark">✓</span>
        </button>
      </div>
      <div v-if="multiSelect" class="clarify-actions">
        <el-button :disabled="busy || !checked.size" type="primary" @click="submitMulti">提交（已选 {{ checked.size }} 项）</el-button>
        <el-button :disabled="busy" @click="emit('cancel')">忽略</el-button>
      </div>
      <p v-if="question.options.length" class="clarify-hint">↑↓ 选择 · Enter 确认 · Esc 忽略{{ multiSelect ? ' · 勾选后提交' : '' }}</p>
    </template>
    <p v-else class="clarify-hint">请在下方输入框补充当前问题所需的信息。</p>
    <div v-if="isCustomerSearch" class="clarify-actions">
      <el-button :disabled="busy" @click="emit('cancel')">取消查询</el-button>
    </div>
  </div>
</template>
