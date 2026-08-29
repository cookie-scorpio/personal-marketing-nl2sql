import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('窄饼图关闭易裁切外标签并始终提供完整数值列表', async () => {
  const chart = await readFile(new URL('../src/features/conversation/ResultChart.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  assert.match(chart, /width\.value < 680/)
  assert.match(chart, /show: !compactPie\.value/)
  assert.match(chart, /class="pie-readable-legend"/)
  assert.match(chart, /item\.value\.toLocaleString\('zh-CN'\)/)
  assert.match(styles, /\.pie-readable-legend strong[\s\S]*overflow-wrap: anywhere/)
})

test('SQL修复轨迹默认折叠并可核查原因与前后SQL', async () => {
  const workspace = await readFile(new URL('../src/features/conversation/ConversationWorkspace.vue', import.meta.url), 'utf8')
  assert.match(workspace, /message\.payload\.repairs\?\.length/)
  assert.match(workspace, /已自动修复 SQL/)
  assert.match(workspace, /repair\.repair_reason/)
  assert.match(workspace, /repair\.original_sql/)
  assert.match(workspace, /repair\.repaired_sql/)
})
