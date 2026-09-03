/** 质量审计后台消费的后端契约；字段名保持服务端 JSON 的 snake_case。 */

/** 后台管理的模块入口，侧栏导航与主区域内容共用同一组键。 */
export type QualityModule =
  | 'overview'
  | 'health'
  | 'resources'
  | 'sql'
  | 'business'
  | 'logs'
  | 'feedback'
  | 'evaluation'
  | 'insight'

export interface MonitorOverview {
  health_status: 'UP' | 'DEGRADED' | string
  uptime_seconds: number
  process_cpu_load: number
  jvm_heap_used_mb: number
  jvm_heap_max_mb: number
  pending_candidates: number
  active_sessions: number
  business: {
    customers: number
    trend: TrendBucket[]
    status_counts: GroupCount[]
    duration: DurationSummary
    event_counts: GroupCount[]
  }
  candidate_counts_24h: GroupCount[]
}

export interface TrendBucket {
  bucket: string
  total_count: number
  success_count: number
  failure_count: number
}

export interface GroupCount {
  group_key: string
  group_count: number
}

export interface DurationSummary {
  samples: number
  avg_seconds: number
  p50_seconds: number
  p95_seconds: number
  max_seconds: number
}

export interface HealthComponent {
  key: string
  label: string
  healthy: boolean
  detail: string
  pending_events?: number
  adapters?: Array<{ provider: string; available: boolean }>
  query_executor?: ExecutorStats
  quality_executor?: ExecutorStats
}

export interface ExecutorStats {
  name: string
  active_count?: number
  pool_size?: number
  queue_size?: number
  queue_capacity?: number
}

export interface HealthSnapshot {
  status: 'UP' | 'DEGRADED' | string
  started_at: string
  uptime_seconds: number
  java_version: string
  os_name: string
  available_processors: number
  jvm_heap_used_mb: number
  jvm_heap_max_mb: number
  components: HealthComponent[]
}

export interface GpuInfo {
  index: number
  name: string
  utilization_percent: number | null
  memory_used_mb: number | null
  memory_total_mb: number | null
  temperature_celsius: number | null
}

export interface ResourcePoint {
  sampled_at: string
  process_cpu_load: number
  system_cpu_load: number
  jvm_heap_used_mb: number
  jvm_heap_max_mb: number
  gpu_utilization_percent?: number
}

export interface ResourceSnapshot extends ResourcePoint {
  os_total_memory_mb: number
  os_free_memory_mb: number
  os_used_memory_mb: number
  thread_count: number
  peak_thread_count: number
  gpu: GpuInfo[]
  history: ResourcePoint[]
}

export interface SqlHealthSnapshot {
  window_hours: number
  phase_counts: GroupCount[]
  repair_counts: GroupCount[]
  event_type_counts: GroupCount[]
  hourly_trend: Array<{ bucket: string; total_count: number }>
  model_calls: {
    total_calls: number
    completed_calls: number
    failed_calls: number
    avg_elapsed_ms: number
  }
}

export interface BusinessSnapshot {
  window_hours: number
  customers: {
    total_customers: number
    active_customers: number
    vip_customers: number
    by_level: GroupCount[]
    top_regions: GroupCount[]
  }
  status_counts: GroupCount[]
  hourly_trend: TrendBucket[]
  duration: DurationSummary
  active_sessions: number
}

export interface LogFileMeta {
  key: string
  file_name: string
  exists: boolean
  size_bytes: number
  last_modified: string | null
}

export interface LogTail {
  file: string
  file_name: string
  exists: boolean
  lines: string[]
  truncated: boolean
  keyword: string | null
}

/** 数据回流候选：payload 已由后端解码，question/sql/error 提升到顶层供采纳表单预填。 */
export interface CandidateItem {
  id: number
  event_id: string
  event_type: string
  event_summary: string
  task_id: string
  session_id: string
  request_id: string
  user_id: number
  occurred_at: string
  decision: 'ACCEPTED' | 'IGNORED' | null
  review_note: string | null
  reviewed_at: string | null
  payload: Record<string, unknown>
  question_text: string | null
  sql_text: string | null
  error_text: string | null
}

export interface CandidatePage {
  page_no: number
  page_size: number
  total: number
  items: CandidateItem[]
}

/** 全量任务事实：样本回流页在全部审计事实上筛选，任务终态/意图/原文由后端关联提升到顶层。 */
export interface FactItem {
  id: number
  event_id: string
  event_type: string
  event_summary: string
  task_id: string
  session_id: string
  request_id: string
  user_id: number
  occurred_at: string
  decision: 'ACCEPTED' | 'IGNORED' | null
  review_note: string | null
  reviewed_at: string | null
  task_status: string | null
  task_intent: string | null
  question_text: string | null
  sql_text: string | null
  error_text: string | null
}

export interface FactPage {
  page_no: number
  page_size: number
  total: number
  items: FactItem[]
}

/** 任务视角行：一个终态任务一行，终态事件为审核锚点；无锚点事件的历史任务无法审核操作。 */
export interface TaskItem {
  task_id: string
  question_text: string | null
  task_status: string
  task_intent: string | null
  sql_text: string | null
  error_text: string | null
  occurred_at: string
  user_id: number
  event_id: string | null
  event_type: string | null
  event_summary: string | null
  decision: 'ACCEPTED' | 'IGNORED' | null
  review_note: string | null
  reviewed_at: string | null
}

export interface TaskPage {
  page_no: number
  page_size: number
  total: number
  items: TaskItem[]
}

/** 单个任务的事实时间线条目，供详情抽屉回溯执行过程。 */
export interface TaskFact {
  event_id: string
  event_type: string
  event_summary: string
  occurred_at: string
  evaluation_candidate: boolean
  decision: 'ACCEPTED' | 'IGNORED' | null
}

export interface DatasetView {
  /** 雪花 ID 超出 JS Number 安全整数范围，服务端以字符串下发。 */
  id: string
  name: string
  description: string | null
  status: 'DRAFT' | 'PUBLISHED'
  version: number
  item_count: number
  published_at: string | null
  created_at: string
  updated_at: string
  editable: boolean
}

export interface DatasetItemView {
  id: string
  dataset_id: string
  source_event_id: string | null
  source_task_id: string | null
  question_text: string
  expected_sql: string
  note: string | null
  /** 金标意图：评测意图识别准确率时与重放判定对比，可空。 */
  intent_code: string | null
  created_at: string
  updated_at: string
}

export interface DatasetDetail extends DatasetView {
  items: DatasetItemView[]
}

export interface RunView {
  id: string
  dataset_id: string
  dataset_version: number
  trigger_type: 'AUTO_PUBLISH' | 'MANUAL'
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
  total_items: number
  finished_items: number
  passed_items: number
  error_message: string | null
  started_at: string | null
  finished_at: string | null
  created_at: string
}

export interface RunSummary {
  total_items: number
  passed_items: number
  execution_success_items: number
  sql_match_items: number
  result_consistent_items: number
  execution_success_rate: number
  sql_match_rate: number
  result_consistent_rate: number
  pass_rate: number
  /** 意图与澄清维度：意图准确率仅统计已标注金标意图的样本，未标注时为 null。 */
  clarification_items: number
  clarification_rate: number
  intent_judged_items: number
  intent_matched_items: number
  intent_accuracy: number | null
  /** SQL 生成链路维度：解释/校验失败单独计数。 */
  interpret_failed_items: number
  validation_failed_items: number
  validation_pass_rate: number
  avg_elapsed_ms: number
  p50_elapsed_ms: number
  p95_elapsed_ms: number
}

export interface RunItemView {
  id: string
  item_id: string
  question_text: string
  expected_sql: string | null
  generated_sql: string | null
  execution_success: boolean
  sql_match: boolean | null
  result_consistent: boolean | null
  expected_rows: number | null
  actual_rows: number | null
  elapsed_ms: number | null
  outcome: string
  failure_stage: string | null
  /** 重放时系统判定的意图与计划来源，对照金标意图定位误判样本。 */
  intent_code: string | null
  interpretation_source: string | null
  error_message: string | null
}

export interface RunDetail extends RunView {
  dataset: DatasetView
  summary: RunSummary
  items: RunItemView[]
}

/** 优化洞察总览：失败热点矩阵、错误聚类、澄清/修复案例与模型调用成本。 */
export interface InsightSnapshot {
  window_hours: number
  failure_matrix: Array<{
    group_key: string
    failed_count: number
    timed_out_count: number
    degraded_count: number
    total_count: number
  }>
  error_top: GroupCount[]
  clarification_cases: Array<{
    task_id: string
    query_text: string
    clarification_round: number
    status_code: string
    created_at: string
  }>
  repair_cases: Array<{
    task_id: string
    attempt_no: number
    trigger_phase: string
    status_code: string
    failure_reason: string | null
    repair_reason: string | null
    original_sql: string | null
    repaired_sql: string | null
    created_at: string
  }>
  repair_status_counts: GroupCount[]
  model_cost: {
    total_calls: number
    completed_calls: number
    failed_calls: number
    total_tokens: number
    prompt_tokens: number
    completion_tokens: number
    avg_elapsed_ms: number
  }
}
