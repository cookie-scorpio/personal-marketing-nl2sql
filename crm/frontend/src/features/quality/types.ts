/** 质量审计后台消费的后端契约；字段名保持服务端 JSON 的 snake_case。 */

/** 后台管理的八个模块入口，侧栏导航与主区域内容共用同一组键。 */
export type QualityModule =
  | 'overview'
  | 'health'
  | 'resources'
  | 'sql'
  | 'business'
  | 'logs'
  | 'feedback'
  | 'evaluation'

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

export interface DatasetView {
  id: number
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
  id: number
  dataset_id: number
  source_event_id: string | null
  source_task_id: string | null
  question_text: string
  expected_sql: string
  note: string | null
  created_at: string
  updated_at: string
}

export interface DatasetDetail extends DatasetView {
  items: DatasetItemView[]
}

export interface RunView {
  id: number
  dataset_id: number
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
  avg_elapsed_ms: number
  p50_elapsed_ms: number
  p95_elapsed_ms: number
}

export interface RunItemView {
  id: number
  item_id: number
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
  error_message: string | null
}

export interface RunDetail extends RunView {
  dataset: DatasetView
  summary: RunSummary
  items: RunItemView[]
}
