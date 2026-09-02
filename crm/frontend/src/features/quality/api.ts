/** 质量审计后台的接口封装；只覆盖 /api/v1/quality 下的监控与评测端点。 */
import { apiRequest } from '../../app/api'
import type {
  BusinessSnapshot,
  CandidatePage,
  DatasetDetail,
  DatasetItemView,
  DatasetView,
  HealthSnapshot,
  LogFileMeta,
  LogTail,
  MonitorOverview,
  ResourceSnapshot,
  RunDetail,
  RunView,
  SqlHealthSnapshot,
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

export function acceptCandidate(eventId: string, body: { question_text: string; expected_sql: string; note?: string }) {
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

export function fetchDataset(id: number): Promise<DatasetDetail> {
  return apiRequest(`/api/v1/quality/evaluation/datasets/${id}`)
}

export function addDatasetItem(body: { question_text: string; expected_sql: string; note?: string }): Promise<DatasetItemView> {
  return apiRequest('/api/v1/quality/evaluation/datasets/current/items', {
    method: 'POST',
    body: JSON.stringify(body),
  })
}

export function updateDatasetItem(id: number, body: { question_text?: string; expected_sql?: string; note?: string }) {
  return apiRequest(`/api/v1/quality/evaluation/items/${id}`, {
    method: 'PUT',
    body: JSON.stringify(body),
  })
}

export function deleteDatasetItem(id: number) {
  return apiRequest(`/api/v1/quality/evaluation/items/${id}`, { method: 'DELETE' })
}

export function publishDataset(id: number): Promise<{ dataset: DatasetView; run_id: number }> {
  return apiRequest(`/api/v1/quality/evaluation/datasets/${id}/publish`, { method: 'POST' })
}

export function startRun(id: number): Promise<{ run_id: number }> {
  return apiRequest(`/api/v1/quality/evaluation/datasets/${id}/runs`, { method: 'POST' })
}

export function fetchRuns(pageNo: number, pageSize: number): Promise<{ page_no: number; page_size: number; total: number; items: RunView[] }> {
  const query = new URLSearchParams({ page_no: String(pageNo), page_size: String(pageSize) })
  return apiRequest(`/api/v1/quality/evaluation/runs?${query.toString()}`)
}

export function fetchRun(id: number): Promise<RunDetail> {
  return apiRequest(`/api/v1/quality/evaluation/runs/${id}`)
}
