package com.boc.nl2sql.quality.persistence;

import com.boc.nl2sql.quality.event.QualityEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

@Repository
public class QualityEventRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public QualityEventRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public void save(QualityEvent event) {
        jdbc.update("""
                INSERT INTO audit_event(
                    event_id,request_id,session_id,task_id,message_id,user_id,model_call_id,sql_attempt_id,
                    evaluation_run_id,evaluation_candidate,event_type,schema_version,source_module,event_source,
                    event_summary,occurred_at,payload_json)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE event_id=VALUES(event_id)
                """, event.eventId(), event.requestId(), event.sessionId(), event.taskId(), event.messageId(),
                event.userId(), event.modelCallId(), event.sqlAttemptId(), event.evaluationRunId(),
                event.evaluationCandidate(), event.eventType(), event.schemaVersion(), event.sourceModule(),
                event.eventSource(), event.summary(), Timestamp.valueOf(event.occurredAt()),
                json.writeValueAsString(event.payload()));
    }

    public List<Map<String, Object>> timeline(String taskId, long afterId, int size) {
        return jdbc.queryForList("""
                SELECT id,event_id,event_type,schema_version,source_module,event_source,request_id,session_id,task_id,
                       message_id,user_id,model_call_id,sql_attempt_id,evaluation_run_id,evaluation_candidate,
                       event_summary,occurred_at,created_at,payload_json
                  FROM audit_event
                 WHERE task_id=? AND id>?
                 ORDER BY id
                 LIMIT ?
                """, taskId, afterId, size);
    }
}
