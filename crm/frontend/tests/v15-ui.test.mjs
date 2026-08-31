import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('agent步骤流运行中分步展示、结束折叠为摘要', async () => {
  const workspace = await readFile(new URL('../src/features/conversation/ConversationWorkspace.vue', import.meta.url), 'utf8')
  const steps = await readFile(new URL('../src/features/conversation/AgentSteps.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  assert.match(workspace, /import AgentSteps from '\.\/AgentSteps\.vue'/)
  assert.match(workspace, /stepLog/)
  assert.match(workspace, /<AgentSteps v-if=/)
  // 运行中显示全部已到达步骤；结束折叠为一行摘要，不再展开长列表。
  assert.match(steps, /v-if="running/)
  assert.match(steps, /已完成/) ; assert.match(steps, /个步骤/)
  assert.match(steps, /用时/)
  assert.match(styles, /\.agent-steps/)
})

test('展示层不出现内部合并模板与原始客户编号后缀', async () => {
  const workspace = await readFile(new URL('../src/features/conversation/ConversationWorkspace.vue', import.meta.url), 'utf8')
  // 消息展示直接使用 display_query（后端保证为用户原话），前端不得自行拼接内部口径文本。
  assert.doesNotMatch(workspace, /上一条已完成查询条件/)
  assert.doesNotMatch(workspace, /本次追问（明确更正优先/)
})

test('客户浮窗锁定原条件并用空输入框自动附加筛选', async () => {
  const panel = await readFile(new URL('../src/features/conversation/ClarifyPanel.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  assert.match(panel, /fixedConstraint/)
  assert.match(panel, /watch\(keyword/)
  assert.match(panel, /setTimeout\(\(\) => void runSearch\(true\), 280\)/)
  assert.match(panel, /v-if="!keyword" #prefix/)
  assert.match(panel, /没有符合当前筛选条件的客户/)
  assert.doesNotMatch(panel, /recognized_slots\?\.\['检索词'\]/)
  assert.doesNotMatch(panel, /clarify-search-btn/)
  assert.doesNotMatch(panel, /#append/)
  assert.match(styles, /\.clarify-search-icon/)
})

test('唯一客户显示只读卡片且用户气泡不由前端脱敏', async () => {
  const workspace = await readFile(new URL('../src/features/conversation/ConversationWorkspace.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  assert.match(workspace, /message\.payload\.resolved_customer/)
  assert.match(workspace, /当前查询客户/)
  assert.match(styles, /\.resolved-customer-card/)
  assert.match(workspace, /userMessage\(pending\.display \|\| pending\.text\)/)
})
