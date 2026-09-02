package com.boc.nl2sql.dao.evaluation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 数据回流候选池的联合查询：audit_event 候选事实与人工审核结论的只读视图。 */
@Repository
public class EvalCandidateRepository {
    private final JdbcTemplate jdbc;

    public EvalCandidateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 分页返回候选事实及其审核状态。
     * status 取值 pending（未审核）、ACCEPTED、IGNORED 或 all；其他值按 all 处理。
     */
    public List<Map<String, Object>> page(String status, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String condition = reviewCondition(status, args);
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList("""
                SELECT a.id, a.event_id, a.event_type, a.event_summary, a.task_id, a.session_id,
                       a.request_id, a.user_id, a.occurred_at, a.payload_json,
                       r.decision, r.note AS review_note, r.reviewed_at
                  FROM audit_event a
                  LEFT JOIN eval_candidate_review r ON r.event_id = a.event_id
                 WHERE a.evaluation_candidate = TRUE""" + condition + """
                 ORDER BY a.occurred_at DESC, a.id DESC
                 LIMIT ? OFFSET ?
                """, args.toArray());
    }

    public long count(String status) {
        List<Object> args = new ArrayList<>();
        String condition = reviewCondition(status, args);
        Long total = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM audit_event a
                  LEFT JOIN eval_candidate_review r ON r.event_id = a.event_id
                 WHERE a.evaluation_candidate = TRUE""" + condition, Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 读取单个候选事实的载荷，供采纳时预填问题与 SQL。 */
    public Map<String, Object> payload(String eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, task_id, event_type, payload_json FROM audit_event WHERE event_id = ? AND evaluation_candidate = TRUE",
                eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 审核状态过滤条件统一在此构造，查询参数一律绑定而非拼接。 */
    private String reviewCondition(String status, List<Object> args) {
        switch (status == null ? "all" : status.toUpperCase()) {
            case "PENDING":
                // 未审核行 decision 为 NULL，不能用 NOT IN 判断（NULL 参与比较结果为 UNKNOWN）。
                return " AND r.event_id IS NULL";
            case "ACCEPTED", "IGNORED":
                args.add(status.toUpperCase());
                return " AND r.decision = ?";
            default:
                return "";
        }
    }
}
