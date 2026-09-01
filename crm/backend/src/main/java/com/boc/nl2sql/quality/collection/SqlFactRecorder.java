package com.boc.nl2sql.quality.collection;

import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** SQL 诊断日志、结构化事实和业务指标的统一 F Adapter。 */
@Component
public class SqlFactRecorder {
    private final ObjectMapper json;
    private final QualityFacts facts;
    private final MeterRegistry meters;

    public SqlFactRecorder(ObjectMapper json, QualityFacts facts, MeterRegistry meters) {
        this.json = json;
        this.facts = facts;
        this.meters = meters;
    }

    public void record(String taskId, String requestId, Long userId, String source, String phase,
                       String sql, Map<String, Object> parameters, String outcome) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("task_id", taskId);
        entry.put("request_id", requestId);
        entry.put("source", source);
        entry.put("phase", phase);
        entry.put("sql", sql);
        entry.put("parameters", parameters == null ? Map.of() : parameters);
        entry.put("outcome", outcome);
        LoggerFactory.getLogger("SQL_REVIEW").info(json.writeValueAsString(entry));

        facts.publish(QualityFact.builder(QualityEventType.SQL_ATTEMPT_RECORDED, "EXECUTION")
                .requestId(requestId).taskId(taskId).userId(userId).sqlAttemptId(attemptId(taskId, sql))
                .summary(phase + (outcome == null ? "" : " " + outcome))
                .details(entry).evaluationCandidate(isCandidate(phase)).build());
        meters.counter("nl2sql.sql.attempts", "phase", phase == null ? "UNKNOWN" : phase).increment();
    }

    private boolean isCandidate(String phase) {
        return phase != null && java.util.Set.of("REJECTED", "SQL_ERROR", "RESULT_MISMATCH", "TIMED_OUT").contains(phase);
    }

    private String attemptId(String taskId, String sql) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(((taskId == null ? "" : taskId) + "\n" + (sql == null ? "" : sql)).getBytes(StandardCharsets.UTF_8));
            return (taskId == null ? "sql" : taskId) + ":" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception impossible) {
            return (taskId == null ? "sql" : taskId) + ":unknown";
        }
    }
}
