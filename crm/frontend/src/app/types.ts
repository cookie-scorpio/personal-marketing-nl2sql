/** 前端消费的后端 API 契约；字段名保持服务端 JSON 的 snake_case。 */
/** 可被后端正式授予的三类身份。三级业务数据范围另由 business_scope_level 表达。 */
export type RoleCode = 'CUSTOMER_MANAGER' | 'QUALITY_AUDITOR' | 'PERMISSION_ADMIN'
export type BusinessScopeLevel = 'CUSTOMER_MANAGER' | 'TEAM_LEAD' | 'ORG_MANAGER'

export interface CurrentUser {
  user_id: number
  username: string
  display_name: string
  role: RoleCode
  business_scope_level?: BusinessScopeLevel
  available_roles: RoleCode[]
  employee_no?: string
  region_code?: string
  branch_id?: string
  manager_id?: string
}

export interface LoginResponse {
  access_token: string
  token_type: string
  expires_in: number
  user: CurrentUser
}

export interface EncryptedPassword {
  key_id: string
  encrypted_password: string
}

export interface PasswordPublicKey {
  key_id: string
  algorithm: 'RSA-OAEP-256'
  public_key: string
}

export interface RegistrationResponse {
  username: string
  employee_no: string
  account_status: 'PENDING'
  message: string
}

export interface RoleGrant {
  role: RoleCode
  business_scope_level?: BusinessScopeLevel
}

/** 权限管理页的账号摘要；密码哈希和令牌绝不会被该接口返回。 */
export interface PermissionAdminAccount {
  user_id: number
  employee_no?: string
  username: string
  display_name: string
  account_status: 'PENDING' | 'ACTIVE' | string
  enabled: boolean
  roles: RoleGrant[]
  region_code?: string
  branch_id?: string
  manager_id?: string
}

export interface ClarificationQuestion {
  question_id: string
  type: string
  prompt: string
  options: string[]
  recognized_slots: Record<string, string>
  input_types?: string[]
  recommended_option?: string | null
  multi_select?: boolean
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
  total?: number
  page_no?: number
  page_size?: number
  offset?: number
  has_more?: boolean
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
  resolved_customer?: {
    customer_id: string
    name: string
    branch_id: string
    mobile: string
  }
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
