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

/**
 * F 对 SQL 修复事实和兼容查询投影的集中实现。
 *
 * <p>C 决定是否进入修复以及最多尝试几次，D 生成修复候选，E 重新进行安全校验；
 * 本组件只在各步骤发生后保存轨迹，不包含任何是否修复或如何继续的业务判断。</p>
 */
@Component
public class RepairFactStore {
    private final JdbcTemplate jdbc;
    private final QualityFacts facts;

    public RepairFactStore(JdbcTemplate jdbc, QualityFacts facts) {
        this.jdbc = jdbc;
        this.facts = facts;
    }

    /**
     * 记录一次修复开始，包含触发阶段、原 SQL、失败原因和 C 给出的修复说明。
     * 由 {@code QueryTaskProcessor.requestRepair()} 在确认进入修复后调用。
     */
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

    /** 记录 D 已生成修复候选，并保存完整候选 SQL。 */
    public void generated(String taskId, Long userId, int attempt, String repairedSql) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='GENERATED',repaired_sql=? WHERE task_id=? AND attempt_no=?",
                repairedSql, taskId, attempt));
        publish(QualityEventType.REPAIR_CANDIDATE_GENERATED, taskId, userId, attempt, "GENERATED", true,
                Map.of("candidate_sql", value(repairedSql)));
    }

    /** 记录修复候选通过 SQL 安全检查并被任务处理器采用；成功步骤本身不标记为失败候选。 */
    public void applied(String taskId, Long userId, int attempt) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='APPLIED' WHERE task_id=? AND attempt_no=?", taskId, attempt));
        publish(QualityEventType.REPAIR_APPLIED, taskId, userId, attempt, "APPLIED", false, Map.of());
    }

    /** 记录修复候选被拒绝及拒绝原因，并进入评测候选池。 */
    public void rejected(String taskId, Long userId, int attempt, String reason) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='REJECTED',failure_reason=? WHERE task_id=? AND attempt_no=?",
                shorten(reason), taskId, attempt));
        publish(QualityEventType.REPAIR_REJECTED, taskId, userId, attempt, "REJECTED", true,
                Map.of("reason", value(reason)));
    }

    /** 记录模型没有形成有效修复候选或模型调用失败。 */
    public void modelFailed(String taskId, Long userId, int attempt, String reason) {
        bestEffort(() -> jdbc.update("UPDATE query_sql_repair SET status_code='MODEL_FAILED',failure_reason=? WHERE task_id=? AND attempt_no=?",
                shorten(reason), taskId, attempt));
        publish(QualityEventType.REPAIR_MODEL_FAILED, taskId, userId, attempt, "MODEL_FAILED", true,
                Map.of("reason", value(reason)));
    }

    /**
     * 按修复次数读取兼容投影。当前由 TaskSnapshots 调用，用于组成任务状态响应。
     */
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

    /**
     * 构造统一修复事实，并使用 taskId:repair:attempt 作为 SQL 尝试关联编号。
     */
    private void publish(QualityEventType type, String taskId, Long userId, int attempt, String summary,
                         boolean candidate, Map<String, Object> details) {
        facts.publish(QualityFact.builder(type, "CONVERSATION")
                .requestId(MDC.get("requestId")).taskId(taskId).userId(userId)
                .sqlAttemptId(taskId + ":repair:" + attempt).summary(summary)
                .detail("attempt", attempt).details(details).evaluationCandidate(candidate).build());
    }

    /**
     * 尽力维护旧的 query_sql_repair 查询投影。投影失败不会打断 C，正式事实仍走异步补偿路径。
     */
    private void bestEffort(Runnable write) {
        try { write.run(); } catch (RuntimeException ignored) { /* 原始事实仍由异步采集与补偿保存。 */ }
    }

    /** 将写入旧投影的长错误说明限制在 1000 个字符以内。 */
    private String shorten(String value) {
        String safe = value == null ? "" : value;
        return safe.substring(0, Math.min(1000, safe.length()));
    }

    /** Map.of 不接受 null，构造事实载荷前统一转换为空字符串。 */
    private String value(String value) { return value == null ? "" : value; }

    /**
     * 兼容投影的只读结果模型，由 TaskSnapshots 转换成查询任务响应。
     */
    public record RepairTrace(long repairId, int attemptNo, String triggerPhase, String status,
                              String originalSql, String failureReason, String repairReason, String repairedSql,
                              LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
