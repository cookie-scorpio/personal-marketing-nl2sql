import type { ConversationMessage, QueryResult } from '../../app/types'

/** 查询结果的产品级浏览上限；结果页组件和复制文本共用，避免界面口径不一致。 */
export const QUERY_RESULT_MAX_PAGES = 50

/**
 * 历史结果可能没有 analysis 等字段；只补展示容器，不重新生成业务结论。
 * row_count 是旧版按当前页行数生成的展示指标，可以用同一响应中的可靠 total 修正为完整查询总数。
 */
export function normalizeResult(result: QueryResult): QueryResult {
  const metrics = (result.metrics || []).map(metric => metric.key === 'row_count' && typeof result.total === 'number'
    ? { ...metric, value: result.total, note: '符合条件的全部结果' }
    : metric)
  return {
    ...result,
    columns: result.columns || [],
    rows: result.rows || [],
    metrics,
    charts: result.charts || [],
    analysis: {
      overview: result.analysis?.overview || result.summary || '',
      insights: result.analysis?.insights || [],
      suggestions: result.analysis?.suggestions || [],
    },
  }
}
export function messageText(message: ConversationMessage): string {
  if (message.role_code === 'USER') return message.content
  const payload = message.payload, result = payload?.result
  if (!result) return payload?.question ? [payload.question.prompt, ...(payload.question.options || []), ...(payload.question.candidates || []).map(c => `${c.name} ${c.customer_id} ${c.mobile}`)].join('\n') : payload?.error?.message || message.content
  const r = normalizeResult(result)
  const table = r.rows.length ? [r.columns.map(c => c.label).join('\t'), ...r.rows.map(row => r.columns.map(c => String(row[c.key] ?? '—')).join('\t'))].join('\n') : ''
  const pageSize = r.page_size || Math.max(1, r.rows.length)
  const visibleTotal = typeof r.total === 'number'
    ? Math.min(r.total, pageSize * QUERY_RESULT_MAX_PAGES)
    : r.rows.length
  const pageState = typeof r.total === 'number' && r.total > visibleTotal
    ? `，最多查看前${visibleTotal}条（${QUERY_RESULT_MAX_PAGES}页）`
    : r.has_more ? '，后续仍有数据' : '，已到最后一页'
  const pagination = typeof r.total === 'number'
    ? `分页：共${r.total}条，第${r.page_no || 1}页，每页${pageSize}条${pageState}`
    : ''
  return [r.title, r.summary, pagination, ...r.metrics.map(m => `${m.label}：${m.value ?? '—'}${m.unit || ''}`), ...r.analysis.insights, table].filter(Boolean).join('\n\n')
}
