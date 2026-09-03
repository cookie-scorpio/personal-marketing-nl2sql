package com.boc.nl2sql.service.monitoring;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 优化洞察页的只读聚合：失败热点、错误类型 Top N、澄清热点、修复轨迹与模型调用成本。
 * 全部为窗口聚合查询，不返回客户明细；失败矩阵按意图 × 终态定位提示词与生成链路的薄弱点。
 */
@Service
public class InsightService {
    private final JdbcTemplate jdbc;

    public InsightService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 洞察总览：一次返回优化洞察页全部模块的数据，窗口小时数决定统计范围。 */
    public Map<String, Object> overview(int hours) {
        int safeHours = Math.max(1, Math.min(hours, 24 * 366));
        Timestamp since = Timestamp.valueOf(LocalDateTime.now().minusHours(safeHours));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("window_hours", safeHours);
        // 失败热点矩阵：意图 × 终态，定位哪类问题在哪个终态最集中。
        result.put("failure_matrix", jdbc.queryForList("""
                SELECT COALESCE(NULLIF(intent_code, ''), 'UNKNOWN') AS group_key,
                       COALESCE(SUM(status_code = 'FAILED'), 0) AS failed_count,
                       COALESCE(SUM(status_code = 'TIMED_OUT'), 0) AS timed_out_count,
                       COALESCE(SUM(status_code = 'DEGRADED'), 0) AS degraded_count,
                       COUNT(*) AS total_count
                  FROM query_task
                 WHERE status_code IN ('FAILED', 'TIMED_OUT', 'DEGRADED') AND created_at >= ?
                 GROUP BY group_key
                 ORDER BY total_count DESC
                """, since));
        // 错误类型 Top N：按事实摘要聚类，摘要已含中文结论与错误码。
        result.put("error_top", jdbc.queryForList("""
                SELECT event_summary AS group_key, COUNT(*) AS group_count
                  FROM audit_event
                 WHERE event_type IN ('QUERY_SQL_ERROR', 'QUERY_RESULT_MISMATCH', 'RUNTIME_FAILURE', 'QUERY_TIMED_OUT')
                   AND occurred_at >= ?
                 GROUP BY event_summary
                 ORDER BY group_count DESC
                 LIMIT 10
                """, since));
        // 澄清热点：澄清轮次最多的问题清单，澄清过多说明提问质量或提示词有待改进。
        result.put("clarification_cases", jdbc.queryForList("""
                SELECT task_id, query_text, clarification_round, status_code, created_at
                  FROM query_task
                 WHERE clarification_round >= 1 AND created_at >= ?
                 ORDER BY clarification_round DESC, created_at DESC
                 LIMIT 10
                """, since));
        // 修复案例：最近的 SQL 修复轨迹，含修复前后的 SQL 对照与结论。
        result.put("repair_cases", jdbc.queryForList("""
                SELECT task_id, attempt_no, trigger_phase, status_code,
                       LEFT(failure_reason, 200) AS failure_reason, LEFT(repair_reason, 200) AS repair_reason,
                       LEFT(original_sql, 160) AS original_sql, LEFT(repaired_sql, 160) AS repaired_sql, created_at
                  FROM query_sql_repair
                 WHERE created_at >= ?
                 ORDER BY created_at DESC
                 LIMIT 10
                """, since));
        // 修复结论分布：修复成功率 = APPLIED / STARTED，衡量修复提示词与额度策略的有效性。
        result.put("repair_status_counts", jdbc.queryForList("""
                SELECT status_code AS group_key, COUNT(*) AS group_count
                  FROM query_sql_repair
                 WHERE created_at >= ?
                 GROUP BY status_code
                """, since));
        // 性能与成本：模型调用的 token 消耗与耗时，来自事实载荷中的 usage 字段。
        result.put("model_cost", jdbc.queryForMap("""
                SELECT COUNT(*) AS total_calls,
                       COALESCE(SUM(event_type = 'MODEL_CALL_COMPLETED'), 0) AS completed_calls,
                       COALESCE(SUM(event_type IN ('MODEL_CALL_FAILED', 'MODEL_RESPONSE_REJECTED')), 0) AS failed_calls,
                       COALESCE(SUM(CAST(JSON_EXTRACT(payload_json, '$.usage.total_tokens') AS DECIMAL(14, 0))), 0) AS total_tokens,
                       COALESCE(SUM(CAST(JSON_EXTRACT(payload_json, '$.usage.prompt_tokens') AS DECIMAL(14, 0))), 0) AS prompt_tokens,
                       COALESCE(SUM(CAST(JSON_EXTRACT(payload_json, '$.usage.completion_tokens') AS DECIMAL(14, 0))), 0) AS completion_tokens,
                       COALESCE(AVG(CASE WHEN event_type = 'MODEL_CALL_COMPLETED'
                                         THEN CAST(JSON_EXTRACT(payload_json, '$.elapsed_ms') AS DECIMAL(12, 2)) END), 0) AS avg_elapsed_ms
                  FROM audit_event
                 WHERE event_type IN ('MODEL_CALL_COMPLETED', 'MODEL_CALL_FAILED', 'MODEL_RESPONSE_REJECTED')
                   AND occurred_at >= ?
                """, since));
        return result;
    }
}
