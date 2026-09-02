import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

test('审计管理员默认进入后台管理且侧栏按视图切换', async () => {
  const app = await readFile(new URL('../src/App.vue', import.meta.url), 'utf8')
  // 默认视图：审计身份直接落在后台管理，客户经理保持问数工作区。
  assert.match(app, /if \(user\.value\?\.role === 'QUALITY_AUDITOR'\) return 'quality'/)
  // 问数视图中新建会话与会话列表仍然存在；后台管理视图中被模块导航替换。
  assert.match(app, /<QualityAdminNav :module="qualityModule" @navigate="key => \(qualityModule = key\)" \/>/)
  assert.match(app, /v-else-if="isQueryIdentity"/)
  assert.match(app, /新建会话/)
  // 主区域由后台容器按模块渲染，不再出现空占位文案。
  assert.match(app, /<QualityAdminPage v-if="currentView === 'quality'" :module="qualityModule"/)
  assert.doesNotMatch(app, /当前暂无可操作内容/)
})

test('后台控制台提供八个模块入口并覆盖监控与评测', async () => {
  const nav = await readFile(new URL('../src/features/quality/QualityAdminNav.vue', import.meta.url), 'utf8')
  const page = await readFile(new URL('../src/features/quality/QualityAdminPage.vue', import.meta.url), 'utf8')
  for (const key of ['overview', 'health', 'resources', 'sql', 'business', 'logs', 'feedback', 'evaluation']) {
    assert.match(nav, new RegExp(`key: '${key}'`), `缺少模块入口 ${key}`)
  }
  for (const panel of ['OverviewPanel', 'SystemHealthPanel', 'ResourceMonitorPanel', 'SqlHealthPanel',
    'BusinessMonitorPanel', 'LogCenterPanel', 'DataFeedbackPanel', 'EvaluationPanel']) {
    assert.match(page, new RegExp(panel), `容器未挂载 ${panel}`)
  }
})

test('监控面板轮询在卸载时清理且不弹全局错误', async () => {
  const polling = await readFile(new URL('../src/features/quality/usePolling.ts', import.meta.url), 'utf8')
  const resources = await readFile(new URL('../src/features/quality/modules/ResourceMonitorPanel.vue', import.meta.url), 'utf8')
  assert.match(polling, /onBeforeUnmount\(\(\) => window\.clearInterval\(timer\)\)/)
  assert.match(polling, /visibilityState === 'hidden'/)
  // GPU 缺失时展示明确文案而不是伪造数据。
  assert.match(resources, /未检测到 GPU/)
  assert.match(resources, /nvidia-smi/)
})

test('数据回流采纳必须金标且评测发布后不可修改', async () => {
  const feedback = await readFile(new URL('../src/features/quality/modules/DataFeedbackPanel.vue', import.meta.url), 'utf8')
  const evaluation = await readFile(new URL('../src/features/quality/modules/EvaluationPanel.vue', import.meta.url), 'utf8')
  // 采纳表单要求问题与期望 SQL 同时存在，评测金标不允许为空。
  assert.match(feedback, /期望 SQL（评测金标）/)
  assert.match(feedback, /问题原文与期望 SQL 都不能为空/)
  // 发布前确认不可逆语义；运行报告展示四个评测维度。
  assert.match(evaluation, /发布后评测集内容不可修改/)
  assert.match(evaluation, /执行成功率/)
  assert.match(evaluation, /SQL 匹配率/)
  assert.match(evaluation, /结果一致率/)
  assert.match(evaluation, /P50 \/ P95/)
})

test('后台接口契约覆盖监控与评测端点', async () => {
  const api = await readFile(new URL('../src/features/quality/api.ts', import.meta.url), 'utf8')
  for (const path of [
    '/api/v1/quality/monitor/overview',
    '/api/v1/quality/monitor/health',
    '/api/v1/quality/monitor/resources',
    '/api/v1/quality/monitor/sql-health',
    '/api/v1/quality/monitor/business',
    '/api/v1/quality/monitor/logs',
    '/api/v1/quality/evaluation/candidates',
    '/api/v1/quality/evaluation/datasets',
    '/api/v1/quality/evaluation/runs',
  ]) {
    assert.match(api, new RegExp(path.replaceAll('/', '\\/')), `缺少端点 ${path}`)
  }
})
