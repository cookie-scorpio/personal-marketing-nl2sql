export type RoleCode = 'CUSTOMER_MANAGER' | 'TEAM_LEAD' | 'ORG_MANAGER'

export interface CurrentUser {
  user_id: number
  username: string
  display_name: string
  role: RoleCode
  region_code: string
  branch_id?: string
  manager_id?: string
}

export interface LoginResponse {
  access_token: string
  token_type: string
  expires_in: number
  user: CurrentUser
}

export interface ClarificationQuestion {
  question_id: string
  type: string
  prompt: string
  options: string[]
  recognized_slots: Record<string, string>
  input_types?: string[]
  candidates?: Array<{ customer_id: string; name: string; branch_id: string; mobile: string }>
}

export interface ColumnMeta {
  key: string
  label: string
  data_type: string
  sensitive: boolean
  role?: 'DIMENSION' | 'TIME' | 'MEASURE'
  unit?: string
  aggregation?: string
  weight_key?: string
}

export interface QueryResult {
  result_type: string
  title: string
  summary: string
  columns: ColumnMeta[]
  rows: Record<string, unknown>[]
  metrics: Array<{ key?: string; label: string; value: unknown; unit?: string; note?: string }>
  charts: ChartSpec[]
  analysis: {
    overview: string
    insights: string[]
    suggestions: string[]
  }
  sql_preview: string
  data_as_of: string
  interpretation_source: 'RULE' | 'DEEPSEEK' | string
  confidence: number
  fallback?: { reason: string; template_id?: string; data_available: boolean; suggestions: string[] }
}

export interface ChartSpec {
  type: 'BAR' | 'LINE' | 'AREA' | 'PIE' | 'SCATTER' | 'HEATMAP'
  title: string
  dimension_key: string
  series: Array<{ key: string; label: string; unit?: string }>
  dimension_label?: string
  secondary_dimension_key?: string
  reason?: string
}

export interface TaskStatus {
  task_id: string
  session_id: string
  status: string
  progress: number
  message: string
  intent?: string
  clarification_round: number
  repair_attempts?: number
  repairs?: SqlRepairTrace[]
  execution_timeout_seconds?: number
  cancellable?: boolean
  question?: ClarificationQuestion
  confirmation?: {
    confirm_token: string
    risk_level: string
    message: string
    reasons: string[]
  }
  result?: QueryResult
  error?: { message: string }
  state_version: number
  thinking_enabled: boolean
  display_query?: string
  legacy_recovered?: boolean
  legacy_notice?: string
  created_at?: string
  updated_at?: string
}

export interface SqlRepairTrace {
  repair_id: number
  attempt_no: number
  trigger_phase: 'VALIDATION' | 'EXECUTION' | 'RESULT_REVIEW' | string
  status: 'STARTED' | 'GENERATED' | 'APPLIED' | 'REJECTED' | 'MODEL_FAILED' | string
  original_sql: string
  failure_reason: string
  repair_reason: string
  repaired_sql?: string
  created_at: string
  updated_at: string
}

export interface ConversationMessage {
  message_id: number | string
  task_id: string
  role_code: 'USER' | 'ASSISTANT'
  content: string
  payload?: TaskStatus
  created_at?: string
  updated_at?: string
  feedback?: 'LIKE' | 'DISLIKE' | 'NONE' | null
}
export interface ConversationSummary { session_id: string; title: string; active_task_id?: string; created_at: string; updated_at: string }
export interface ConversationDetail extends ConversationSummary {
  messages: ConversationMessage[]
  has_more: boolean
  context?: { query: string; customer_id?: string; source_task_id?: string }
}

export interface SubmitQueryResponse {
  task_id: string
  session_id: string
  status: string
  progress: number
  status_url: string
}

export interface HistoryItem {
  history_id: string
  task_id: string
  query_text: string
  intent_code?: string
  status_code: string
  sql_summary?: string
  result_summary?: string
  created_at: string
}

export interface PageResult<T> {
  items: T[]
  total: number
  page_no: number
  page_size: number
}
