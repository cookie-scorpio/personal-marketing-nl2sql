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

test('审计身份保留智能问数并以组件重建代替整页刷新', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const workspace = await readFile(new URL('../src/features/conversation/ConversationWorkspace.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  // 审计员拥有问数和后台两个工作区；身份切换不能再调用浏览器整页刷新。
  assert.match(app, /role === 'CUSTOMER_MANAGER' \|\| user\.value\?\.role === 'QUALITY_AUDITOR'/)
  assert.match(app, /\{ value: 'query' as const, label: '智能问数' \}/)
  assert.match(app, /\{ value: 'quality' as const, label: '后台管理' \}/)
  assert.doesNotMatch(app, /window\.location\.reload/)
  // 会话组件和历史侧栏的 key 都包含当前身份，防止切换后继续展示旧身份内存状态。
  assert.match(app, /:key="`\$\{user\?\.user_id\}-\$\{user\?\.role\}`"/)
  assert.match(workspace, /user\.value\?\.role === 'QUALITY_AUDITOR'/)
  // 身份卡与退出按钮由同一网格行对齐，身份控件显式使用深色背景，避免浏览器默认白底。
  assert.match(styles, /\.sidebar-footer \{[^}]*grid-template-columns:/)
  assert.match(styles, /\.user-card \{[^}]*background: #302e2d;/)
})

test('左下账号信息始终显示五位工号且不随身份改变', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  // 工号读取账号级 employee_no，不再根据当前身份拼接区域、网点、经理或审计范围。
  assert.match(app, /const employeeNoLabel = computed/)
  assert.match(app, /\^\[0-9\]\{5\}\$/)
  assert.match(app, /`工号 \$\{employeeNo\}`/)
  assert.match(app, /<strong>\{\{ employeeNoLabel \}\}<\/strong>/)
  assert.doesNotMatch(app, /const scopeLabel = computed/)
})

test('权限管理员账号与身份操作区域和其他身份一样固定在侧栏底部', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  // 权限管理员没有会话列表，账号框必须用弹性上边距吸收中间空白，才能带动身份卡和退出按钮下沉。
  assert.match(app, /v-else class="sidebar-role-note"/)
  assert.match(app, /<div class="sidebar-note">/)
  assert.match(app, /<div class="sidebar-footer">/)
  assert.match(styles, /\.sidebar-note \{[^}]*margin: auto 0 12px;/)
})
