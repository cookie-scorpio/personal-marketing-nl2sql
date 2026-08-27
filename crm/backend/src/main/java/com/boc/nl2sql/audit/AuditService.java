package com.boc.nl2sql.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 审计日志仅记录行为摘要，不写入完整 Prompt、敏感参数或查询结果。 */
    public void record(String requestId, String taskId, Long userId, String eventType, String summary) {
        jdbcTemplate.update("""
                INSERT INTO audit_event(request_id, task_id, user_id, event_type, event_summary)
                VALUES (?, ?, ?, ?, ?)
                """, requestId, taskId, userId, eventType, summary);
    }
}
