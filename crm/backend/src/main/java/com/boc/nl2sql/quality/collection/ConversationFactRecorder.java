package com.boc.nl2sql.quality.collection;

import com.boc.nl2sql.quality.event.QualityFact;
import com.boc.nl2sql.quality.event.QualityEventType;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/** 会话诊断日志与正式事实的统一 F Adapter。 */
@Component
public class ConversationFactRecorder {
    private final ObjectMapper json;
    private final QualityFacts facts;

    public ConversationFactRecorder(ObjectMapper json, QualityFacts facts) {
        this.json = json;
        this.facts = facts;
    }

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

    private boolean isCandidate(String status) {
        return status != null && java.util.Set.of("FAILED", "TIMED_OUT", "DEGRADED").contains(status);
    }
}
