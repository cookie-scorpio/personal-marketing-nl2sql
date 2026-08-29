package com.boc.nl2sql.execution.application;

import com.boc.nl2sql.conversation.api.SqlRepairResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/** 持久化最多两次的修复轨迹。原始数据库报错、结果值和模型思考均不得写入。 */
@Component
public class SqlRepairStore {
    private final JdbcTemplate jdbc;

    public SqlRepairStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void start(String taskId, int attempt, String trigger, String originalSql,
                      String failureReason, String repairReason) {
        jdbc.update("""
                INSERT INTO query_sql_repair(task_id,attempt_no,trigger_phase,status_code,original_sql,failure_reason,repair_reason)
                VALUES(?,?,?,'STARTED',?,?,?)
                """, taskId, attempt, trigger, originalSql, shorten(failureReason), shorten(repairReason));
    }

    public void generated(String taskId, int attempt, String repairedSql) {
        jdbc.update("UPDATE query_sql_repair SET status_code='GENERATED',repaired_sql=? WHERE task_id=? AND attempt_no=?",
                repairedSql, taskId, attempt);
    }

    public void applied(String taskId, int attempt) {
        jdbc.update("UPDATE query_sql_repair SET status_code='APPLIED' WHERE task_id=? AND attempt_no=?",
                taskId, attempt);
    }

    public void rejected(String taskId, int attempt, String reason) {
        jdbc.update("UPDATE query_sql_repair SET status_code='REJECTED',failure_reason=? WHERE task_id=? AND attempt_no=?",
                shorten(reason), taskId, attempt);
    }

    public void modelFailed(String taskId, int attempt, String reason) {
        jdbc.update("UPDATE query_sql_repair SET status_code='MODEL_FAILED',failure_reason=? WHERE task_id=? AND attempt_no=?",
                shorten(reason), taskId, attempt);
    }

    public List<SqlRepairResponse> list(String taskId) {
        return jdbc.query("""
                SELECT repair_id,attempt_no,trigger_phase,status_code,original_sql,failure_reason,repair_reason,
                       repaired_sql,created_at,updated_at
                  FROM query_sql_repair WHERE task_id=? ORDER BY attempt_no
                """, (rs, row) -> new SqlRepairResponse(rs.getLong("repair_id"), rs.getInt("attempt_no"),
                rs.getString("trigger_phase"), rs.getString("status_code"), rs.getString("original_sql"),
                rs.getString("failure_reason"), rs.getString("repair_reason"), rs.getString("repaired_sql"),
                rs.getTimestamp("created_at").toLocalDateTime(), rs.getTimestamp("updated_at").toLocalDateTime()), taskId);
    }

    private String shorten(String value) {
        String safe = value == null || value.isBlank() ? "未提供可公开的失败原因" : value.strip();
        return safe.substring(0, Math.min(1000, safe.length()));
    }
}
