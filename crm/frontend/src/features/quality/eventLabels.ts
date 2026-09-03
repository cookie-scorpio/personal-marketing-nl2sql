/**
 * 质量事实事件类型与历史摘要的统一中文释义。
 * 总览页"质量事实分类"与数据回流页"类型/摘要"列共用，避免多处映射漂移。
 */

/** 与后端 QualityEventType 枚举一一对应的中文释义。 */
export const EVENT_LABELS: Record<string, string> = {
  QUERY_RECEIVED: '查询已接收',
  QUERY_STATE_CHANGED: '查询状态变化',
  QUERY_ASKING: '澄清提问',
  QUERY_CLARIFIED: '已澄清',
  QUERY_CONFIRMING: '等待用户确认',
  QUERY_CONFIRMED: '用户已确认',
  QUERY_CANCELLED: '用户取消',
  QUERY_SQL_ERROR: 'SQL 执行报错',
  QUERY_RESULT_MISMATCH: '结果不一致',
  QUERY_RESULT_REVIEW_UNAVAILABLE: '结果复核暂不可用',
  QUERY_FALLBACK: '模板兜底',
  QUERY_SUCCESS: '问数成功',
  QUERY_FAILED: '问数失败',
  QUERY_TIMED_OUT: '问数超时',
  QUERY_DEGRADED: '降级完成',
  CONVERSATION_MESSAGE_RECORDED: '会话消息记录',
  CONVERSATION_DELETED: '会话删除',
  MODEL_CALL_COMPLETED: '模型调用完成',
  MODEL_CALL_FAILED: '模型调用失败',
  MODEL_RESPONSE_REJECTED: '模型响应被拒',
  SQL_ATTEMPT_RECORDED: 'SQL 尝试记录',
  REPAIR_STARTED: '修复启动',
  REPAIR_CANDIDATE_GENERATED: '产出修复候选',
  REPAIR_APPLIED: '修复生效',
  REPAIR_REJECTED: '修复候选被拒',
  REPAIR_MODEL_FAILED: '模型修复失败',
  FEEDBACK_CHANGED: '用户评价变化',
  ACCESS_LOGIN_SUCCEEDED: '登录成功',
  ACCESS_LOGIN_FAILED: '登录失败',
  ACCESS_REGISTRATION_SUBMITTED: '注册申请提交',
  ACCESS_AUTHENTICATION_FAILED: '身份认证失败',
  ACCESS_AUTHORIZATION_DENIED: '访问被拒绝',
  QUALITY_TIMELINE_VIEWED: '审计轨迹查阅',
  EVAL_CANDIDATE_REVIEWED: '候选审核完成',
  EVAL_DATASET_PUBLISHED: '评测集发布',
  EVAL_RUN_STARTED: '评测运行启动',
  EVAL_RUN_ITEM_COMPLETED: '评测条目完成',
  EVAL_RUN_FINISHED: '评测运行结束',
  RUNTIME_FAILURE: '运行异常',
}

export function eventLabel(key: string): string {
  return EVENT_LABELS[key] ?? key
}

/** 事实载荷中出现的任务状态 → 中文。 */
const STATUS_LABELS: Record<string, string> = {
  RECEIVED: '已接收', INTENT_ANALYZING: '意图识别', ASKING: '等待澄清', SQL_GENERATING: '生成SQL',
  VALIDATING: '校验中', CONFIRMING: '待确认', EXECUTING: '执行中', REPAIRING: '修复中',
  FALLING_BACK: '模板兜底', RESULT_REVIEWING: '结果复核', PACKAGING: '整理结果',
  SUCCESS: '成功', DEGRADED: '降级完成', FAILED: '失败', TIMED_OUT: '超时', CANCELLED: '已取消',
}

const FEEDBACK_LABELS: Record<string, string> = { LIKE: '好评', DISLIKE: '差评', NONE: '无评价' }

const ROLE_LABELS: Record<string, string> = { USER: '用户', ASSISTANT: '助手' }

/** 任务意图编码 → 中文，样本回流页"意图"筛选与列展示共用。 */
const INTENT_LABELS: Record<string, string> = {
  CUSTOMER_FILTER: '客户筛选', TRANSACTION_ANALYSIS: '交易分析', GENERIC_ANALYSIS: '通用分析',
  MARKETING_ANALYSIS: '营销分析', ASSET_ANALYSIS: '资产分析', FOLLOWUP: '追问衔接', GREETING: '问候',
}

/** 任务状态英文键 → 中文释义；样本回流页"任务终态"列与筛选共用。 */
export function statusLabel(key: string | null | undefined): string {
  if (!key) return ''
  const status = (key ?? '').toUpperCase()
  return STATUS_LABELS[status] ?? key
}

/** 意图编码 → 中文释义，未知编码原样返回。 */
export function intentLabel(key: string | null | undefined): string {
  if (!key) return ''
  return INTENT_LABELS[key] ?? key
}

/** 意图筛选下拉的全部已知编码。 */
export function intentKeys(): string[] {
  return Object.keys(INTENT_LABELS)
}

/** SQL 尝试事实的阶段英文键 → 中文（兼容旧摘要前缀）。 */
const PHASE_LABELS: Record<string, string> = {
  GENERATED: '生成候选SQL', REJECTED: '校验拒绝', EXECUTING: '开始执行', EXECUTED: '执行成功',
  SQL_ERROR: '执行报错', RESULT_ALIGNED: '结果复核通过', RESULT_MISMATCH: '结果不一致',
  RESULT_REVIEW_UNAVAILABLE: '结果复核暂不可用', TIMED_OUT: '执行超时', CANCELLED: '已取消',
  TOOL_RESULT: '工具预检', DATABASE_ERROR: '数据库执行错误',
}

const PURPOSE_LABELS: Record<string, string> = {
  INTERPRET: '意图识别与计划生成', REPAIR: 'SQL 修复', RESULT_REVIEW: '结果结构复核',
}

/**
 * 兼容历史事实摘要的展示层翻译：新事实由后端直接写入中文摘要，
 * 历史英文摘要（如 "ASSISTANT DEGRADED"、"NONE -> DISLIKE"）在展示时按已知模式转换。
 */
export function translateSummary(summary: string | null | undefined): string {
  const text = (summary ?? '').trim()
  if (!text) return ''
  if (EVENT_LABELS[text] && !text.includes(' ')) return EVENT_LABELS[text]
  // 评价变化：NONE -> DISLIKE
  const feedback = text.match(/^(LIKE|DISLIKE|NONE)\s*->\s*(LIKE|DISLIKE|NONE)$/)
  if (feedback) return `评价变化：${FEEDBACK_LABELS[feedback[1]]} → ${FEEDBACK_LABELS[feedback[2]]}`
  // 修复轨迹：STARTED / GENERATED / APPLIED / REJECTED / MODEL_FAILED
  if (PHASE_LABELS[text] && !text.includes(' ')) return PHASE_LABELS[text]
  // 会话状态快照：ASSISTANT DEGRADED / USER FAILED
  const roleStatus = text.match(/^(USER|ASSISTANT)\s+(\S+)$/)
  if (roleStatus) {
    const role = ROLE_LABELS[roleStatus[1]] ?? roleStatus[1]
    const status = STATUS_LABELS[roleStatus[2]] ?? roleStatus[2]
    return `${role}${roleStatus[1] === 'ASSISTANT' ? '任务' : '消息'}：${status}`
  }
  // SQL 尝试：SQL_ERROR 聚合函数使用位置错误（1111）
  const spaceIndex = text.indexOf(' ')
  if (spaceIndex > 0) {
    const prefix = text.slice(0, spaceIndex)
    if (PHASE_LABELS[prefix]) return `${PHASE_LABELS[prefix]}：${text.slice(spaceIndex + 1)}`
    if (PURPOSE_LABELS[prefix]) return `${PURPOSE_LABELS[prefix]}：${text.slice(spaceIndex + 1)}`
  }
  // 历史英文常量：旧版本写入的英文摘要在展示层统一兜底翻译
  const legacy: Record<string, string> = {
    'candidate accepted': '候选已采纳',
    'candidate ignored': '候选已忽略',
    'dataset item updated': '评测条目已更新',
    'provider request accepted': '查询已接收',
    'invalid credentials': '登录失败：用户名或密码不正确',
    'account pending': '登录失败：账号待审批',
    'account disabled': '登录失败：账号已停用',
    QUALITY_AUDITOR: '登录成功（质量审计员）',
    QUALITY_ADMIN: '登录成功（质量管理员）',
    CUSTOMER_MANAGER: '登录成功（客户经理）',
    PERMISSION_ADMIN: '登录成功（权限管理员）',
    CUSTOMER_SELECTION: '澄清提问：请选择客户',
    CUSTOMER_CONFIRM: '澄清提问：请确认客户',
    CUSTOMER_SCOPE: '澄清提问：客户范围确认',
    FOLLOWUP_CONTEXT: '澄清提问：追问缺少上下文',
    DISPLAY_CONFLICT: '澄清提问：展示口径冲突',
    CONFLICT: '澄清提问：条件冲突',
    TIME_BASIS: '澄清提问：时间口径确认',
  }
  // 身份切换：identity switched to QUALITY_AUDITOR
  const switched = text.match(/^identity switched to (\w+)$/)
  if (switched) {
    const label = legacy[switched[1]]?.replace('登录成功（', '').replace('）', '') ?? switched[1]
    return `身份切换为 ${label}`
  }
  const datasetVersion = text.match(/^dataset published as version (\d+)$/)
  if (datasetVersion) return `评测集已发布为版本 ${datasetVersion[1]}`
  if (legacy[text]) return legacy[text]
  // 运行异常摘要曾是异常类名，展示时补中文前缀
  if (/^[A-Za-z$]+Exception$/.test(text)) return `运行异常：${text}`
  return text
}
