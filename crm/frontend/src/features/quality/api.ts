/** 质量审计后台的接口封装；只覆盖 /api/v1/quality 下的监控与评测端点。 */
import { apiRequest } from '../../app/api'
import type {
  BusinessSnapshot,
  CandidatePage,
  DatasetDetail,
  DatasetItemView,
  DatasetView,
  FactPage,
  HealthSnapshot,
  InsightSnapshot,
  LogFileMeta,
  LogTail,
  MonitorOverview,
  ResourceSnapshot,
  RunDetail,
  RunView,
  SqlHealthSnapshot,
  TaskFact,
  TaskPage,
} from './types'

export function fetchOverview(): Promise<MonitorOverview> {
  return apiRequest('/api/v1/quality/monitor/overview')
}

export function fetchHealth(): Promise<HealthSnapshot> {
  return apiRequest('/api/v1/quality/monitor/health')
}

export function fetchResources(): Promise<ResourceSnapshot> {
  return apiRequest('/api/v1/quality/monitor/resources')
}

export function fetchSqlHealth(hours: number): Promise<SqlHealthSnapshot> {
  return apiRequest(`/api/v1/quality/monitor/sql-health?hours=${hours}`)
}

export function fetchBusiness(hours: number): Promise<BusinessSnapshot> {
  return apiRequest(`/api/v1/quality/monitor/business?hours=${hours}`)
}

export function fetchLogCatalog(): Promise<LogFileMeta[]> {
  return apiRequest('/api/v1/quality/monitor/logs/catalog')
}

export function fetchLog(file: string, lines: number, keyword: string): Promise<LogTail> {
  const query = new URLSearchParams({ file, lines: String(lines) })
  if (keyword.trim()) query.set('keyword', keyword.trim())
  return apiRequest(`/api/v1/quality/monitor/logs?${query.toString()}`)
}

export function fetchCandidates(status: string, pageNo: number, pageSize: number): Promise<CandidatePage> {
  const query = new URLSearchParams({ status, page_no: String(pageNo), page_size: String(pageSize) })
  return apiRequest(`/api/v1/quality/evaluation/candidates?${query.toString()}`)
}

/** 全量任务事实分页：多维筛选参数留空即不过滤，hours=0 表示不限时间窗口。 */
export function fetchFacts(params: {
  status: string
  pageNo: number
  pageSize: number
  hours: number
  eventType?: string
  taskStatus?: string
  intent?: string
  keyword?: string
}): Promise<FactPage> {
  const query = new URLSearchParams({
    status: params.status,
    page_no: String(params.pageNo),
    page_size: String(params.pageSize),
    hours: String(params.hours),
  })
  if (params.eventType) query.set('event_type', params.eventType)
  if (params.taskStatus) query.set('task_status', params.taskStatus)
  if (params.intent) query.set('intent', params.intent)
  if (params.keyword && params.keyword.trim()) query.set('keyword', params.keyword.trim())
  return apiRequest(`/api/v1/quality/evaluation/facts?${query.toString()}`)
}

/** 单个任务的全链路事实时间线。 */
export function fetchTaskFacts(taskId: string): Promise<TaskFact[]> {
  return apiRequest(`/api/v1/quality/evaluation/facts/task/${taskId}`)
}

/** 任务视角分页：每个终态任务一行，默认视图；筛选参数语义与 fetchFacts 一致。 */
export function fetchTaskView(params: {
  status: string
  pageNo: number
  pageSize: number
  hours: number
  taskStatus?: string
  intent?: string
  keyword?: string
}): Promise<TaskPage> {
  const query = new URLSearchParams({
    status: params.status,
    page_no: String(params.pageNo),
    page_size: String(params.pageSize),
    hours: String(params.hours),
  })
  if (params.taskStatus) query.set('task_status', params.taskStatus)
  if (params.intent) query.set('intent', params.intent)
  if (params.keyword && params.keyword.trim()) query.set('keyword', params.keyword.trim())
  return apiRequest(`/api/v1/quality/evaluation/facts/tasks?${query.toString()}`)
}

export function acceptCandidate(eventId: string, body: { question_text: string; expected_sql: string; note?: string; intent_code?: string }) {
  return apiRequest(`/api/v1/quality/evaluation/candidates/${eventId}/accept`, {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function ignoreCandidate(eventId: string, note?: string) {
  return apiRequest(`/api/v1/quality/evaluation/candidates/${eventId}/ignore`, {
    method: 'POST',
    body: JSON.stringify({ note: note || null }),
  })
}

export function fetchDatasets(): Promise<DatasetView[]> {
  return apiRequest('/api/v1/quality/evaluation/datasets')
}

export function fetchDataset(id: string): Promise<DatasetDetail> {
  return apiRequest(`/api/v1/quality/evaluation/datasets/${id}`)
}

export function addDatasetItem(body: { question_text: string; expected_sql: string; note?: string; intent_code?: string }): Promise<DatasetItemView> {
  return apiRequest('/api/v1/quality/evaluation/datasets/current/items', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateDatasetItem(id: string, body: { question_text?: string; expected_sql?: string; note?: string; intent_code?: string }) {
  return apiRequest(`/api/v1/quality/evaluation/items/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function deleteDatasetItem(id: string) {
  return apiRequest(`/api/v1/quality/evaluation/items/${id}`, { method: 'DELETE' })
}

export function publishDataset(id: string): Promise<{ dataset: DatasetView; run_id: string }> {
  return apiRequest(`/api/v1/quality/evaluation/datasets/${id}/publish`, { method: 'POST' })
}

export function startRun(id: string): Promise<{ run_id: string }> {
  return apiRequest(`/api/v1/quality/evaluation/datasets/${id}/runs`, { method: 'POST' })
}

export function fetchRuns(pageNo: number, pageSize: number): Promise<{ page_no: number; page_size: number; total: number; items: RunView[] }> {
  const query = new URLSearchParams({ page_no: String(pageNo), page_size: String(pageSize) })
  return apiRequest(`/api/v1/quality/evaluation/runs?${query.toString()}`)
}

export function fetchRun(id: string): Promise<RunDetail> {
  return apiRequest(`/api/v1/quality/evaluation/runs/${id}`)
}

/** 优化洞察总览：失败热点、错误聚类、澄清/修复案例与模型调用成本的窗口聚合。 */
export function fetchInsight(hours: number): Promise<InsightSnapshot> {
  return apiRequest(`/api/v1/quality/insight/overview?hours=${hours}`)
}
