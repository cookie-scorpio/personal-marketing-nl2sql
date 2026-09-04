import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import ts from 'typescript'

async function moduleFrom(source, replace = []) {
  let code = await readFile(new URL(source, import.meta.url), 'utf8')
  for (const [from, to] of replace) code = code.replaceAll(from, to)
  const compiled = ts.transpileModule(code, { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText
  return import('data:text/javascript;base64,' + Buffer.from(compiled).toString('base64'))
}
const text = await moduleFrom('../src/features/conversation/messageText.ts')
test('旧结果仅补齐空容器，保留原行值', () => {
  const old = { title: '旧回答', summary: '42人', rows: [{ n: 42 }], columns: [{ key: 'n', label: '人数' }] }
  const value = text.normalizeResult(old)
  assert.deepEqual(value.rows, [{ n: 42 }]); assert.deepEqual(value.charts, []); assert.deepEqual(value.analysis.insights, [])
})
test('分页明细的主要结果使用完整总数而不是当前页行数', () => {
  const value = text.normalizeResult({
    title: '客户名单',
    summary: '共1681条',
    total: 1681,
    rows: Array.from({ length: 20 }, (_, index) => ({ id: index })),
    columns: [{ key: 'id', label: '编号' }],
    metrics: [{ key: 'row_count', label: '结果行数', value: 20, unit: '行', note: '当前返回结果' }],
  })
  assert.equal(value.metrics[0].value, 1681)
  assert.equal(value.metrics[0].note, '符合条件的全部结果')
})
test('复制回复包括历史结果与明细，不只复制阶段说明', () => {
  const copied = text.messageText({ role_code: 'ASSISTANT', content: '完成', payload: { result: { title: '旧回答', summary: '42人', columns: [{ key: 'n', label: '人数' }], rows: [{ n: 42 }] } } })
  assert.match(copied, /旧回答/); assert.match(copied, /人数\n42/)
})
test('复制澄清问题与用户文本保持原始含义', () => {
  assert.equal(text.messageText({ role_code: 'USER', content: '后四位0012' }), '后四位0012')
  assert.match(text.messageText({ role_code: 'ASSISTANT', payload: { question: { prompt: '选择客户', options: ['编号'] } } }), /选择客户\n编号/)
})
test('旧账号迟到的成功和401均不污染新账号令牌', async () => {
  const originalStorage = globalThis.localStorage, originalFetch = globalThis.fetch
  const storage = new Map()
  globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }
  try {
    const api = await moduleFrom('../src/app/api.ts', [['import.meta.env.VITE_API_BASE_URL', "''"]])
    for (const status of [200, 401]) {
      let release
      globalThis.fetch = () => new Promise(resolve => { release = resolve })
      api.setToken('owner-A')
      const pending = api.apiRequest('/api/v1/conversations')
      api.setToken('owner-B')
      release(new Response(JSON.stringify({ code: status === 200 ? 0 : 401001, data: ['owner-A-private-session'] }), { status }))
      await assert.rejects(pending, error => error.code === 409009)
      assert.equal(api.getToken(), 'owner-B')
    }
  } finally { globalThis.localStorage = originalStorage; globalThis.fetch = originalFetch }
})
