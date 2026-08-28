import type { ConversationMessage, QueryResult } from '../../app/types'

/** v1.0结果没有analysis等新字段；只补展示容器，不重新生成或计算历史数据。 */
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
  return [r.title, r.summary, ...r.metrics.map(m => `${m.label}：${m.value ?? '—'}${m.unit || ''}`), ...r.analysis.insights, ...r.analysis.suggestions, table, r.sql_preview ? `SQL：\n${r.sql_preview}` : ''].filter(Boolean).join('\n\n')
}
