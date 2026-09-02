import type { ConversationMessage, QueryResult } from '../../app/types'

/** 历史结果可能没有 analysis 等字段；只补展示容器，不重新生成或计算历史数据。 */
export function normalizeResult(result: QueryResult): QueryResult {
  return { ...result, columns: result.columns || [], rows: result.rows || [], metrics: result.metrics || [], charts: result.charts || [],
    analysis: { overview: result.analysis?.overview || result.summary || '', insights: result.analysis?.insights || [], suggestions: result.analysis?.suggestions || [] } }
}
export function messageText(message: ConversationMessage): string {
  if (message.role_code === 'USER') return message.content
  const payload = message.payload, result = payload?.result
  if (!result) return payload?.question ? [payload.question.prompt, ...(payload.question.options || []), ...(payload.question.candidates || []).map(c => `${c.name} ${c.customer_id} ${c.mobile}`)].join('\n') : payload?.error?.message || message.content
  const r = normalizeResult(result)
  const table = r.rows.length ? [r.columns.map(c => c.label).join('\t'), ...r.rows.map(row => r.columns.map(c => String(row[c.key] ?? '—')).join('\t'))].join('\n') : ''
  const pagination = typeof r.total === 'number' ? `分页：共${r.total}条，第${r.page_no || 1}页，每页${r.page_size || r.rows.length}条${r.has_more ? '，后续仍有数据' : '，已到最后一页'}` : ''
  return [r.title, r.summary, pagination, ...r.metrics.map(m => `${m.label}：${m.value ?? '—'}${m.unit || ''}`), ...r.analysis.insights, table].filter(Boolean).join('\n\n')
}
