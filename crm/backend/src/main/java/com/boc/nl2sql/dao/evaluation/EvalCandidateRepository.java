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

    /** 读取单个事实的载荷，供采纳时预填问题与 SQL；全量回流模式下不限定历史候选标记。 */
    public Map<String, Object> payload(String eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT event_id, task_id, event_type, payload_json FROM audit_event WHERE event_id = ?",
                eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 按任务编号批量读取问题原文，作为事实载荷缺少 query_text 时的兜底。
     * 返回 task_id 到原文的映射；载荷中只有 SQL 而没有问题的候选（如 SQL 尝试、修复轨迹）由此保证能回显原问句。
     * 兜底顺序：query_task.query_text → query_task.merged_query_text → conversation_message 中该任务的用户提问，
     * 确保会话行缺失或原文为空的历史事件也能从消息表找回各自任务的原文，不会互相串用其他任务的问题。
     */
    public Map<String, String> questionTextsByTaskIds(List<String> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(taskIds.size(), "?"));
        List<Object> args = new ArrayList<>(taskIds);
        Map<String, String> result = new java.util.HashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT task_id, query_text, merged_query_text FROM query_task WHERE task_id IN (%s)
                """.formatted(placeholders), args.toArray())) {
            String taskId = (String) row.get("task_id");
            String text = (String) row.get("query_text");
            if (text == null || text.isBlank()) text = (String) row.get("merged_query_text");
            if (taskId != null && text != null && !text.isBlank()) result.put(taskId, text);
        }
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT task_id, content FROM conversation_message
                 WHERE role_code = 'USER' AND message_key = 'query' AND task_id IN (%s)
                 ORDER BY message_id
                """.formatted(placeholders), args.toArray())) {
            String taskId = (String) row.get("task_id");
            String text = (String) row.get("content");
            if (taskId != null && text != null && !text.isBlank()) result.putIfAbsent(taskId, text);
        }
        return result;
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

    /**
     * 全量任务事实分页：不再依赖 evaluation_candidate 标记，支持事件类型、任务终态、
     * 意图、关键词与时间窗口的组合筛选，供样本回流页浏览与沉淀评测样本。
     * 任务终态与意图来自 query_task 关联，关键词同时匹配任务原问句与事实载荷。
     */
    public List<Map<String, Object>> factPage(String reviewStatus, List<String> eventTypes, List<String> taskStatuses,
            String intent, String keyword, int hours, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = factWhere(reviewStatus, eventTypes, taskStatuses, intent, keyword, hours, args);
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(("""
                SELECT a.id, a.event_id, a.event_type, a.event_summary, a.task_id, a.session_id,
                       a.request_id, a.user_id, a.occurred_at, a.payload_json,
                       r.decision, r.note AS review_note, r.reviewed_at,
                       t.status_code AS task_status, t.intent_code AS task_intent
                  FROM audit_event a
                  LEFT JOIN eval_candidate_review r ON r.event_id = a.event_id
                  LEFT JOIN query_task t ON t.task_id = a.task_id
                 WHERE %s
                 ORDER BY a.occurred_at DESC, a.id DESC
                 LIMIT ? OFFSET ?
                """).formatted(where), args.toArray());
    }

    /** 全量任务事实计数，筛选条件与 {@link #factPage} 完全一致。 */
    public long factCount(String reviewStatus, List<String> eventTypes, List<String> taskStatuses,
            String intent, String keyword, int hours) {
        List<Object> args = new ArrayList<>();
        String where = factWhere(reviewStatus, eventTypes, taskStatuses, intent, keyword, hours, args);
        Long total = jdbc.queryForObject(("""
                SELECT COUNT(*)
                  FROM audit_event a
                  LEFT JOIN eval_candidate_review r ON r.event_id = a.event_id
                  LEFT JOIN query_task t ON t.task_id = a.task_id
                 WHERE %s
                """).formatted(where), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 单个任务的全链路事实时间线，按发生时间正序，供详情抽屉回溯执行过程。 */
    public List<Map<String, Object>> eventsByTaskId(String taskId) {
        return jdbc.queryForList("""
                SELECT a.event_id, a.event_type, a.event_summary, a.occurred_at,
                       a.evaluation_candidate, r.decision
                  FROM audit_event a
                  LEFT JOIN eval_candidate_review r ON r.event_id = a.event_id
                 WHERE a.task_id = ?
                 ORDER BY a.occurred_at, a.id
                """, taskId);
    }

    /**
     * 任务视角分页：每个终态任务一行，以终态事件作为审核锚点（终态事件类型约定为 QUERY_<status>，
     * 见 QueryTaskProcessor.recordTerminal；同一任务多条终态事件取最新一条）。
     * 没有终态事件的历史任务仍可展示但无法审核操作。
     */
    public List<Map<String, Object>> taskPage(String reviewStatus, List<String> taskStatuses,
            String intent, String keyword, int hours, int limit, long offset) {
        List<Object> args = new ArrayList<>();
        String where = taskWhere(reviewStatus, taskStatuses, intent, keyword, hours, args);
        args.add(limit);
        args.add(offset);
        return jdbc.queryForList(("""
                SELECT t.task_id, t.query_text, t.merged_query_text, t.status_code AS task_status,
                       t.intent_code AS task_intent, t.sql_text, t.error_message,
                       t.updated_at AS occurred_at, t.user_id,
                       e.event_id, e.event_type, e.event_summary,
                       r.decision, r.note AS review_note, r.reviewed_at
                  FROM query_task t
                  LEFT JOIN audit_event e
                         ON e.task_id = t.task_id
                        AND e.event_type = CONCAT('QUERY_', t.status_code)
                        AND e.id = (SELECT MAX(e2.id) FROM audit_event e2
                                    WHERE e2.task_id = t.task_id
                                      AND e2.event_type = CONCAT('QUERY_', t.status_code))
                  LEFT JOIN eval_candidate_review r ON r.event_id = e.event_id
                 WHERE %s
                 ORDER BY t.updated_at DESC
                 LIMIT ? OFFSET ?
                """).formatted(where), args.toArray());
    }

    /** 任务视角计数，筛选条件与 {@link #taskPage} 完全一致。 */
    public long taskCount(String reviewStatus, List<String> taskStatuses,
            String intent, String keyword, int hours) {
        List<Object> args = new ArrayList<>();
        String where = taskWhere(reviewStatus, taskStatuses, intent, keyword, hours, args);
        Long total = jdbc.queryForObject(("""
                SELECT COUNT(*)
                  FROM query_task t
                  LEFT JOIN audit_event e
                         ON e.task_id = t.task_id
                        AND e.event_type = CONCAT('QUERY_', t.status_code)
                        AND e.id = (SELECT MAX(e2.id) FROM audit_event e2
                                    WHERE e2.task_id = t.task_id
                                      AND e2.event_type = CONCAT('QUERY_', t.status_code))
                  LEFT JOIN eval_candidate_review r ON r.event_id = e.event_id
                 WHERE %s
                """).formatted(where), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** 任务视角的过滤条件：默认只覆盖终态任务；审核状态挂在终态事件上，无锚点事件的任务视为未处理。 */
    private String taskWhere(String reviewStatus, List<String> taskStatuses,
            String intent, String keyword, int hours, List<Object> args) {
        var conditions = new ArrayList<String>();
        if (taskStatuses != null && !taskStatuses.isEmpty()) {
            conditions.add("t.status_code IN (" + placeholders(taskStatuses) + ")");
            args.addAll(taskStatuses);
        } else {
            conditions.add("t.status_code IN ('SUCCESS', 'DEGRADED', 'FAILED', 'TIMED_OUT', 'CANCELLED')");
        }
        if (intent != null && !intent.isBlank()) {
            conditions.add("t.intent_code = ?");
            args.add(intent);
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("t.query_text LIKE ?");
            args.add("%" + keyword.strip() + "%");
        }
        if (hours > 0) {
            conditions.add("t.updated_at >= ?");
            args.add(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(hours)));
        }
        conditions.add("1 = 1" + reviewCondition(reviewStatus, args));
        return String.join(" AND ", conditions);
    }

    /** 全量事实的动态过滤条件；列表条件走 IN 绑定，关键词同时命中任务原问句与事实载荷。 */
    private String factWhere(String reviewStatus, List<String> eventTypes, List<String> taskStatuses,
            String intent, String keyword, int hours, List<Object> args) {
        var conditions = new ArrayList<String>();
        if (eventTypes != null && !eventTypes.isEmpty()) {
            conditions.add("a.event_type IN (" + placeholders(eventTypes) + ")");
            args.addAll(eventTypes);
        }
        if (taskStatuses != null && !taskStatuses.isEmpty()) {
            conditions.add("t.status_code IN (" + placeholders(taskStatuses) + ")");
            args.addAll(taskStatuses);
        }
        if (intent != null && !intent.isBlank()) {
            conditions.add("t.intent_code = ?");
            args.add(intent);
        }
        if (keyword != null && !keyword.isBlank()) {
            conditions.add("(t.query_text LIKE ? OR a.payload_json LIKE ?)");
            String like = "%" + keyword.strip() + "%";
            args.add(like);
            args.add(like);
        }
        if (hours > 0) {
            conditions.add("a.occurred_at >= ?");
            args.add(java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(hours)));
        }
        conditions.add("1 = 1" + reviewCondition(reviewStatus, args));
        return String.join(" AND ", conditions);
    }

    private String placeholders(List<String> values) {
        return String.join(",", java.util.Collections.nCopies(values.size(), "?"));
    }
}
