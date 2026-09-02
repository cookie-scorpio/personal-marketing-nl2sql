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
        facts.publish(QualityFact.builder(type, "CONVERSATION")
                .requestId(requestId).sessionId(sessionId).taskId(taskId).userId(userId)
                .summary(role + (status == null ? "" : " " + status))
                .details(data).evaluationCandidate(isCandidate(status)).build());
    }

    /** 失败、超时和降级任务进入后续评测候选池。 */
    private boolean isCandidate(String status) {
        return status != null && java.util.Set.of("FAILED", "TIMED_OUT", "DEGRADED").contains(status);
    }
}
