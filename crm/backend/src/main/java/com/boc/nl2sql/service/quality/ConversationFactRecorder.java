package com.boc.nl2sql.service.quality;

import com.boc.nl2sql.domain.quality.QualityFact;
import com.boc.nl2sql.domain.quality.QualityEventType;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 会话诊断日志与正式事实的统一记录器。
 *
 * <p>C 在保存用户消息或助手任务快照后调用本组件。原有日志继续用于现场诊断，
 * {@code audit_event} 中的事实才是后续统计和评测的正式来源。</p>
 */
@Component
public class ConversationFactRecorder {
    private final ObjectMapper json;
    private final QualityFacts facts;

    public ConversationFactRecorder(ObjectMapper json, QualityFacts facts) {
        this.json = json;
        this.facts = facts;
    }

    /** 任务状态的中文释义，写入事实摘要，供数据回流页直接展示。 */
    private static final Map<String, String> STATUS_LABELS = Map.ofEntries(
            Map.entry("RECEIVED", "已接收"), Map.entry("INTENT_ANALYZING", "意图识别"),
            Map.entry("ASKING", "等待澄清"), Map.entry("SQL_GENERATING", "生成SQL"),
            Map.entry("VALIDATING", "校验中"), Map.entry("CONFIRMING", "待确认"),
            Map.entry("EXECUTING", "执行中"), Map.entry("REPAIRING", "修复中"),
            Map.entry("FALLING_BACK", "模板兜底"), Map.entry("RESULT_REVIEWING", "结果复核"),
            Map.entry("PACKAGING", "整理结果"), Map.entry("SUCCESS", "成功"),
            Map.entry("DEGRADED", "降级完成"), Map.entry("FAILED", "失败"),
            Map.entry("TIMED_OUT", "超时"), Map.entry("CANCELLED", "已取消"));

    /**
     * 保存一次消息或任务状态快照。
     *
     * @param role USER 时记录消息事实，ASSISTANT 时记录查询状态变化
     * @param status 当前任务状态，用于识别失败、超时和降级候选
     * @param stateVersion 查询任务维护的状态版本
     * @param content 用户原文或助手状态说明
     * @param taskSnapshot 助手消息对应的完整任务快照，用户消息通常为空
     */
    public void record(String requestId, String sessionId, String taskId, Long userId, String role,
                       String status, long stateVersion, String content, Map<String, Object> taskSnapshot) {
        var data = new LinkedHashMap<String, Object>();
        data.put("request_id", requestId);
        data.put("session_id", sessionId);
        data.put("task_id", taskId);
        data.put("user_id", userId);
        data.put("role", role);
        data.put("status", status);
        data.put("state_version", stateVersion);
        data.put("content", content);
        if (taskSnapshot != null) data.put("task_snapshot", taskSnapshot);
        LoggerFactory.getLogger("CONVERSATION").info(json.writeValueAsString(data));

        QualityEventType type = "ASSISTANT".equals(role)
                ? QualityEventType.QUERY_STATE_CHANGED : QualityEventType.CONVERSATION_MESSAGE_RECORDED;
        String roleLabel = "ASSISTANT".equals(role) ? "助手任务" : "用户消息";
        String statusLabel = STATUS_LABELS.getOrDefault(status, status == null || status.isBlank() ? "" : status);
        String summary = statusLabel.isBlank() ? roleLabel : roleLabel + "：" + statusLabel;
        facts.publish(QualityFact.builder(type, "CONVERSATION")
                .requestId(requestId).sessionId(sessionId).taskId(taskId).userId(userId)
                .summary(summary)
                .details(data).evaluationCandidate(isCandidate(status)).build());
    }

    /** 失败、超时和降级任务进入后续评测候选池。 */
    private boolean isCandidate(String status) {
        return status != null && java.util.Set.of("FAILED", "TIMED_OUT", "DEGRADED").contains(status);
    }
}
