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

test('客户反问面板与消息阅读区各自在边界内滚动', async () => {
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  // 候选客户较多时会压缩上方阅读区；父区裁切越界绘制，反问卡片则在受控高度内自行滚动。
  assert.match(styles, /\.chat-reading-area \{[^}]*min-height: 0;[^}]*overflow: hidden;/)
  assert.match(styles, /\.clarify-panel \{[^}]*max-height: min\(470px, 52dvh\);[^}]*overflow-y: auto;/)
  assert.match(styles, /\.clarify-panel \{[^}]*overscroll-behavior: contain;[^}]*scrollbar-gutter: stable;/)
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

test('后端重启后顶栏服务状态会持续复查并自动恢复', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  // 首次失败不能成为永久状态：固定轮询负责恢复，重新显示页面或网络恢复时立即补查。
  assert.match(app, /SERVICE_HEALTH_POLL_INTERVAL_MS = 10_000/)
  assert.match(app, /setInterval\(\(\) => void checkServiceHealth\(\), SERVICE_HEALTH_POLL_INTERVAL_MS\)/)
  assert.match(app, /addEventListener\('visibilitychange', checkServiceHealthWhenAvailable\)/)
  assert.match(app, /addEventListener\('online', checkServiceHealthWhenAvailable\)/)
  // 健康请求不能使用缓存或无限挂起，组件卸载后也不能留下定时器和在途请求。
  assert.match(app, /fetch\('\/actuator\/health\/liveness', \{ cache: 'no-store', signal: request\.signal \}\)/)
  assert.match(app, /setTimeout\(\(\) => request\.abort\(\), SERVICE_HEALTH_REQUEST_TIMEOUT_MS\)/)
  assert.match(app, /clearInterval\(serviceHealthTimer\)/)
  assert.match(app, /serviceHealthRequest\?\.abort\(\)/)
})

test('业务主页面使用混合流式展示且不影响历史会话', async () => {
  const workspace = await readFile(new URL('../src/features/conversation/ConversationWorkspace.vue', import.meta.url), 'utf8')
  const result = await readFile(new URL('../src/features/conversation/QueryResultView.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  // 只有 SSE 或当前任务状态接口首次带回结果时才播放；打开历史会话会清空标记，避免重复打字。
  assert.match(workspace, /const streamingResultTaskId = ref\(''\)/)
  assert.match(workspace, /update\(status, true\)/)
  assert.match(workspace, /sessionId\.value = id; streamingResultTaskId\.value = '';/)
  assert.match(workspace, /:stream="streamingResultTaskId === message\.task_id"/)
  // 摘要与分析共享字符进度，长文本限制总帧数；表格、图表等待文字结束后一次挂载。
  assert.match(result, /const STREAM_MAX_FRAMES = 48/)
  assert.match(result, /const narrativeSegments = computed/)
  assert.match(result, /Array\.from\(segment\)/)
  assert.match(result, /<ResultChart v-else class="result-structured-enter"/)
  assert.match(result, /<el-table v-else class="result-structured-enter"/)
  // 动画尊重系统减少动态效果设置，逐帧文本不向读屏软件重复播报。
  assert.match(result, /prefers-reduced-motion: reduce/)
  assert.match(result, /class="result-stream-reader"/)
  assert.match(styles, /\.result-stream-reader/)
  assert.match(styles, /@media \(prefers-reduced-motion: reduce\)[^{]*\{[^}]*\.stream-caret/)
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

test('权限管理表头使用主色，姓名直出并提供居中的配置和删除操作', async () => {
  const page = await readFile(new URL('../src/features/permission/PermissionManagementPage.vue', import.meta.url), 'utf8')
  const styles = await readFile(new URL('../src/app/styles.css', import.meta.url), 'utf8')
  // 注册姓名必须原样进入姓名列；两个操作显式居中并给出足够宽度，不产生组件默认省略号。
  assert.match(page, /prop="display_name" label="姓名"/)
  assert.match(page, /class-name="permission-action-column"[^>]*width="190"[^>]*align="center"[^>]*header-align="center"/)
  assert.equal((page.match(/>配置权限<\/el-button>/g) || []).length, 1)
  assert.equal((page.match(/>删除用户<\/el-button>/g) || []).length, 1)
  assert.match(styles, /--el-table-header-bg-color: var\(--accent\)/)
  assert.match(styles, /--el-table-header-text-color: #fff/)
  assert.match(styles, /\.permission-table-wrap \.permission-action-column \.cell \{[^}]*justify-content: center;[^}]*text-overflow: clip;/)
  assert.match(styles, /\.permission-row-actions \{[^}]*justify-content: center;/)
})

test('权限管理员编辑本人时不能取消自己的管理员身份', async () => {
  const page = await readFile(new URL('../src/features/permission/PermissionManagementPage.vue', import.meta.url), 'utf8')
  // 前端以账号编号识别本人并禁用选项；服务端仍有独立校验，界面限制不是唯一安全边界。
  assert.match(page, /const editingOwnAdministrator = computed/)
  assert.match(page, /editing\.value\?\.user_id === user\.value\.user_id/)
  assert.match(page, /label="PERMISSION_ADMIN" :disabled="editingOwnAdministrator"/)
  assert.match(page, /不能撤销当前登录账号自身的权限管理员身份/)
})

test('权限管理员不能删除自己且删除其他用户前必须二次确认', async () => {
  const page = await readFile(new URL('../src/features/permission/PermissionManagementPage.vue', import.meta.url), 'utf8')
  // user_id 作为本人判断依据；禁用态与服务端保护配合，不能用姓名或用户名规避自删限制。
  assert.match(page, /function isOwnAccount\(account: PermissionAdminAccount\)/)
  assert.match(page, /account\.user_id === user\.value\?\.user_id/)
  assert.match(page, /:disabled="isOwnAccount\(row\)"/)
  assert.match(page, /不能删除当前登录账号/)
  assert.match(page, /ElMessageBox\.confirm/)
  assert.match(page, /method: 'DELETE'/)
})

test('注册必须提交姓名并明确处理重复用户名', async () => {
  const page = await readFile(new URL('../src/features/auth/LoginPage.vue', import.meta.url), 'utf8')
  const auth = await readFile(new URL('../src/app/auth.ts', import.meta.url), 'utf8')
  // 姓名进入有效性判断和注册请求；密码规则文案不再暴露实现层的72字节上限。
  assert.match(page, /const displayNameValid = computed/)
  assert.match(page, /<label>姓名/)
  assert.match(page, /register\(employeeNo\.value, displayName\.value\.trim\(\), username\.value, password\.value\)/)
  assert.match(page, /用户名已存在，请修改用户名后重新提交/)
  assert.doesNotMatch(page, />至少8位，最长72字节</)
  assert.match(page, />至少8位</)
  assert.match(auth, /JSON\.stringify\(\{ employee_no: employeeNo, display_name: displayName, username/)
})
