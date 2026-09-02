package com.boc.nl2sql.dao.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台监控页面的只读统计查询。
 * 全部为聚合 SQL，不返回客户明细，结果仅用于运行状态展示。
 */
@Repository
public class QualityMonitorRepository {
    private final JdbcTemplate jdbc;

    public QualityMonitorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 在册客户总数与状态分布；与问数链路使用同一张 dim_customer 快照表。 */
    public Map<String, Object> customerOverview() {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS total_customers,
                       COALESCE(SUM(CASE WHEN status_code = 'ACTIVE' THEN 1 ELSE 0 END), 0) AS active_customers,
                       COALESCE(SUM(CASE WHEN vip_flag = 1 THEN 1 ELSE 0 END), 0) AS vip_customers
                  FROM dim_customer
                """);
    }

    public List<Map<String, Object>> customersByLevel() {
        return jdbc.queryForList("""
                SELECT COALESCE(customer_level_code, 'UNKNOWN') AS group_key, COUNT(*) AS group_count
                  FROM dim_customer
                 WHERE status_code = 'ACTIVE'
                 GROUP BY customer_level_code
                 ORDER BY group_count DESC
                """);
    }

    public List<Map<String, Object>> customersByRegion(int limit) {
        return jdbc.queryForList("""
                SELECT region_code AS group_key, COUNT(*) AS group_count
                  FROM dim_customer
                 WHERE status_code = 'ACTIVE' AND region_code IS NOT NULL
                 GROUP BY region_code
                 ORDER BY group_count DESC
                 LIMIT ?
                """, limit);
    }

    /** 指定时间窗口内的任务状态分布，来源于查询任务状态机的终态。 */
    public List<Map<String, Object>> taskStatusCounts(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT status_code AS group_key, COUNT(*) AS group_count
                  FROM query_task
                 WHERE created_at >= ?
                 GROUP BY status_code
                """, Timestamp.valueOf(since));
    }

    /** 按小时聚合的执行量与成功率趋势；只统计终态任务。 */
    public List<Map<String, Object>> taskHourlyTrend(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') AS bucket,
                       COUNT(*) AS total_count,
                       COALESCE(SUM(CASE WHEN status_code = 'SUCCESS' THEN 1 ELSE 0 END), 0) AS success_count,
                       COALESCE(SUM(CASE WHEN status_code IN ('FAILED', 'TIMED_OUT') THEN 1 ELSE 0 END), 0) AS failure_count
                  FROM query_task
                 WHERE created_at >= ?
                 GROUP BY bucket
                 ORDER BY bucket
                """, Timestamp.valueOf(since));
    }

    /** 任务耗时样本（秒），用于计算平均值和分位数。 */
    public List<Double> taskDurations(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT TIMESTAMPDIFF(MICROSECOND, created_at, updated_at) / 1000000.0 AS duration_seconds
                  FROM query_task
                 WHERE created_at >= ? AND status_code IN ('SUCCESS', 'DEGRADED', 'FAILED', 'TIMED_OUT')
                """, Double.class, Timestamp.valueOf(since));
    }

    /** 审计事实按类型的窗口计数。 */
    public List<Map<String, Object>> auditEventTypeCounts(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT event_type AS group_key, COUNT(*) AS group_count
                  FROM audit_event
                 WHERE occurred_at >= ?
                 GROUP BY event_type
                 ORDER BY group_count DESC
                """, Timestamp.valueOf(since));
    }

    /** 候选事实按类型计数，仍处于候选且未被审核的数据量由此得出。 */
    public List<Map<String, Object>> candidateTypeCounts(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT event_type AS group_key, COUNT(*) AS group_count
                  FROM audit_event
                 WHERE evaluation_candidate = TRUE AND occurred_at >= ?
                 GROUP BY event_type
                 ORDER BY group_count DESC
                """, Timestamp.valueOf(since));
    }

    /** SQL 尝试各阶段的窗口计数；phase 保存在事实 payload 中。 */
    public List<Map<String, Object>> sqlAttemptPhaseCounts(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT COALESCE(JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.phase')), 'UNKNOWN') AS group_key,
                       COUNT(*) AS group_count
                  FROM audit_event
                 WHERE event_type = 'SQL_ATTEMPT_RECORDED' AND occurred_at >= ?
                 GROUP BY group_key
                 ORDER BY group_count DESC
                """, Timestamp.valueOf(since));
    }

    /** SQL 尝试按小时趋势。 */
    public List<Map<String, Object>> sqlAttemptHourlyTrend(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(occurred_at, '%Y-%m-%d %H:00') AS bucket,
                       COUNT(*) AS total_count
                  FROM audit_event
                 WHERE event_type = 'SQL_ATTEMPT_RECORDED' AND occurred_at >= ?
                 GROUP BY bucket
                 ORDER BY bucket
                """, Timestamp.valueOf(since));
    }

    /** SQL 修复轨迹按状态计数，来自 query_sql_repair 投影。 */
    public List<Map<String, Object>> repairStatusCounts(LocalDateTime since) {
        return jdbc.queryForList("""
                SELECT status_code AS group_key, COUNT(*) AS group_count
                  FROM query_sql_repair
                 WHERE created_at >= ?
                 GROUP BY status_code
                """, Timestamp.valueOf(since));
    }

    /** 模型调用成败与耗时窗口统计。 */
    public Map<String, Object> modelCallStats(LocalDateTime since) {
        return jdbc.queryForMap("""
                SELECT COUNT(*) AS total_calls,
                       COALESCE(SUM(CASE WHEN event_type = 'MODEL_CALL_COMPLETED' THEN 1 ELSE 0 END), 0) AS completed_calls,
                       COALESCE(SUM(CASE WHEN event_type IN ('MODEL_CALL_FAILED', 'MODEL_RESPONSE_REJECTED') THEN 1 ELSE 0 END), 0) AS failed_calls,
                       COALESCE(AVG(CASE WHEN event_type = 'MODEL_CALL_COMPLETED'
                                         THEN CAST(JSON_EXTRACT(payload_json, '$.elapsed_ms') AS DECIMAL(12, 2)) END), 0) AS avg_elapsed_ms
                  FROM audit_event
                 WHERE event_type IN ('MODEL_CALL_COMPLETED', 'MODEL_CALL_FAILED', 'MODEL_RESPONSE_REJECTED')
                   AND occurred_at >= ?
                """, Timestamp.valueOf(since));
    }

    /** 未审核的候选事件数量，用于总览和回流页角标。 */
    public long pendingCandidateCount() {
        Long count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM audit_event a
                 WHERE a.evaluation_candidate = TRUE
                   AND NOT EXISTS (SELECT 1 FROM eval_candidate_review r WHERE r.event_id = a.event_id)
                """, Long.class);
        return count == null ? 0 : count;
    }

    /** 数据库连通性探针：返回数据库当前时间，异常向上抛出由健康服务归类。 */
    public LocalDateTime databaseNow() {
        Timestamp now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        return now == null ? null : now.toLocalDateTime();
    }

    /** 活跃会话数（未删除），用于总览。 */
    public long activeSessionCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversation_session WHERE deleted_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }
}
