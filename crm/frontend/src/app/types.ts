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
}

export interface ColumnMeta {
  key: string
  label: string
  data_type: string
  sensitive: boolean
}

export interface QueryResult {
  result_type: string
  title: string
  summary: string
  columns: ColumnMeta[]
  rows: Record<string, unknown>[]
  metrics: Array<{ label: string; value: unknown }>
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
}

export interface ChartSpec {
  type: 'BAR' | 'LINE' | 'PIE'
  title: string
  dimension_key: string
  series: Array<{ key: string; label: string }>
}

export interface TaskStatus {
  task_id: string
  session_id: string
  status: string
  progress: number
  message: string
  intent?: string
  clarification_round: number
  question?: ClarificationQuestion
  confirmation?: {
    confirm_token: string
    risk_level: string
    message: string
    reasons: string[]
  }
  result?: QueryResult
  error?: { message: string }
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
