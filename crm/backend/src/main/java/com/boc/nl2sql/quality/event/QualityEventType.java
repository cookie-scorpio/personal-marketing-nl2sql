package com.boc.nl2sql.quality.event;

/**
 * F 接收的事实类型。
 *
 * <p>枚举值只描述已经发生的事情，不表达“继续执行”“触发修复”等业务命令。
 * 是否进入下一状态仍由对应业务模块决定。</p>
 */
public enum QualityEventType {
    // C：查询接收、状态变化以及用户参与的澄清和确认过程。
    QUERY_RECEIVED,
    QUERY_STATE_CHANGED,
    QUERY_ASKING,
    QUERY_CLARIFIED,
    QUERY_CONFIRMING,
    QUERY_CONFIRMED,
    QUERY_CANCELLED,
    QUERY_SQL_ERROR,
    QUERY_RESULT_MISMATCH,
    QUERY_RESULT_REVIEW_UNAVAILABLE,
    QUERY_FALLBACK,
    QUERY_SUCCESS,
    QUERY_FAILED,
    QUERY_TIMED_OUT,
    QUERY_DEGRADED,

    // C：用户和助手消息，以及会话删除事实。
    CONVERSATION_MESSAGE_RECORDED,
    CONVERSATION_DELETED,

    // D：单次模型 HTTP 调用及模型响应协议检查结果。
    MODEL_CALL_COMPLETED,
    MODEL_CALL_FAILED,
    MODEL_RESPONSE_REJECTED,

    // E/D：SQL 候选在生成、预检、校验、执行和结果复核阶段的事实。
    SQL_ATTEMPT_RECORDED,

    // C 触发修复，D 生成候选，E 重新校验；F 仅记录修复轨迹。
    REPAIR_STARTED,
    REPAIR_CANDIDATE_GENERATED,
    REPAIR_APPLIED,
    REPAIR_REJECTED,
    REPAIR_MODEL_FAILED,

    // F：用户反馈变化。
    FEEDBACK_CHANGED,

    // B：登录、注册、令牌校验和授权拒绝。
    ACCESS_LOGIN_SUCCEEDED,
    ACCESS_LOGIN_FAILED,
    ACCESS_REGISTRATION_SUBMITTED,
    ACCESS_AUTHENTICATION_FAILED,
    ACCESS_AUTHORIZATION_DENIED,

    // F：质量管理员读取任务事实的访问轨迹。
    QUALITY_TIMELINE_VIEWED,

    // 共享基础设施：未被业务异常处理覆盖的运行错误。
    RUNTIME_FAILURE
}
