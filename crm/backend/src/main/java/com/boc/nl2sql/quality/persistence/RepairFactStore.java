package com.boc.nl2sql.quality.persistence;

import com.boc.nl2sql.quality.collection.QualityFacts;
import com.boc.nl2sql.quality.event.QualityEventType;
import com.boc.nl2sql.quality.event.QualityFact;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** F 对 SQL 修复事实和兼容查询投影的集中实现；不包含任何是否修复的业务判断。 */
@Component
public class RepairFactStore {
    private final JdbcTemplate jdbc;
    private final QualityFacts facts;

    public RepairFactStore(JdbcTemplate jdbc, QualityFacts facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    public void started(String taskId, Long userId, int attempt, String trigger, String originalSql,
                        String failureReason, String repairReason) {
        bestEffort(() -> jdbc.update("""
                INSERT INTO query_sql_repair(task_id,attempt_no,trigger_phase,status_code,original_sql,failure_reason,repair_reason)
                VALUES(?,?,?,'STARTED',?,?,?)
                """, taskId, attempt, trigger, originalSql, shorten(failureReason), shorten(repairReason)));
        publish(QualityEventType.REPAIR_STARTED, taskId, userId, attempt, "STARTED", true,
                Map.of("trigger", value(trigger), "original_sql", value(originalSql),
                        "failure_reason", value(failureReason), "repair_reason", value(repairReason)));
    }

    public void generated(String taskId, Long userId, int attempt, String repairedSql) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='GENERATED',repaired_sql=? WHERE task_id=? AND attempt_no=?",
                repairedSql, taskId, attempt));
        publish(QualityEventType.REPAIR_CANDIDATE_GENERATED, taskId, userId, attempt, "GENERATED", true,
                Map.of("candidate_sql", value(repairedSql)));
    }

    public void applied(String taskId, Long userId, int attempt) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='APPLIED' WHERE task_id=? AND attempt_no=?", taskId, attempt));
        publish(QualityEventType.REPAIR_APPLIED, taskId, userId, attempt, "APPLIED", false, Map.of());
    }

    public void rejected(String taskId, Long userId, int attempt, String reason) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='REJECTED',failure_reason=? WHERE task_id=? AND attempt_no=?",
                shorten(reason), taskId, attempt));
        publish(QualityEventType.REPAIR_REJECTED, taskId, userId, attempt, "REJECTED", true,
                Map.of("reason", value(reason)));
    }

    public void modelFailed(String taskId, Long userId, int attempt, String reason) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='MODEL_FAILED',failure_reason=? WHERE task_id=? AND attempt_no=?",
                shorten(reason), taskId, attempt));
        publish(QualityEventType.REPAIR_MODEL_FAILED, taskId, userId, attempt, "MODEL_FAILED", true,
                Map.of("reason", value(reason)));
    }

    public List<RepairTrace> list(String taskId) {
        return jdbc.query("""
                SELECT repair_id,attempt_no,trigger_phase,status_code,original_sql,failure_reason,repair_reason,
                       repaired_sql,created_at,updated_at
                  FROM query_sql_repair WHERE task_id=? ORDER BY attempt_no
                """, (rs, row) -> new RepairTrace(rs.getLong("repair_id"), rs.getInt("attempt_no"),
                rs.getString("trigger_phase"), rs.getString("status_code"), rs.getString("original_sql"),
                rs.getString("failure_reason"), rs.getString("repair_reason"), rs.getString("repaired_sql"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()), taskId);
    }

    private void publish(QualityEventType type, String taskId, Long userId, int attempt, String summary,
                         boolean candidate, Map<String, Object> details) {
        facts.publish(QualityFact.builder(type, "CONVERSATION")
                .requestId(MDC.get("requestId")).taskId(taskId).userId(userId)
                .sqlAttemptId(taskId + ":repair:" + attempt).summary(summary)
                .detail("attempt", attempt).details(details).evaluationCandidate(candidate).build());
    }

    private void bestEffort(Runnable write) {
        try { write.run(); } catch (RuntimeException ignored) { /* 原始事实仍由异步采集与补偿保存。 */ }
    }

    private String shorten(String value) {
        String safe = value == null ? "" : value;
        return safe.substring(0, Math.min(1000, safe.length()));
    }

    private String value(String value) { return value == null ? "" : value; }

    public record RepairTrace(long repairId, int attemptNo, String triggerPhase, String status,
                              String originalSql, String failureReason, String repairReason, String repairedSql,
                              LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
