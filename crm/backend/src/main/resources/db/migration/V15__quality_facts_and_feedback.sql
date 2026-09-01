-- F：把原有审计摘要升级为统一的不可变事实表；旧数据保留并补齐事件信封。
ALTER TABLE audit_event
    ADD COLUMN event_id CHAR(36) NULL AFTER id,
    ADD COLUMN schema_version INT NOT NULL DEFAULT 1 AFTER event_type,
    ADD COLUMN source_module VARCHAR(32) NOT NULL DEFAULT 'LEGACY' AFTER schema_version,
    ADD COLUMN event_source VARCHAR(16) NOT NULL DEFAULT 'ONLINE' AFTER source_module,
    ADD COLUMN session_id VARCHAR(36) NULL AFTER request_id,
    ADD COLUMN message_id BIGINT NULL AFTER task_id,
    ADD COLUMN model_call_id VARCHAR(64) NULL AFTER message_id,
    ADD COLUMN sql_attempt_id VARCHAR(64) NULL AFTER model_call_id,
    ADD COLUMN evaluation_run_id VARCHAR(36) NULL AFTER sql_attempt_id,
    ADD COLUMN evaluation_candidate BOOLEAN NOT NULL DEFAULT FALSE AFTER evaluation_run_id,
    ADD COLUMN occurred_at DATETIME(3) NULL AFTER event_summary,
    ADD COLUMN payload_json JSON NULL AFTER occurred_at;

UPDATE audit_event
SET event_id = UUID(),
    schema_version = 0,
    source_module = 'LEGACY',
    event_source = 'ONLINE',
    occurred_at = created_at,
    payload_json = JSON_OBJECT('legacy_summary', event_summary)
WHERE event_id IS NULL;

ALTER TABLE audit_event
    MODIFY COLUMN event_id CHAR(36) NOT NULL,
    MODIFY COLUMN occurred_at DATETIME(3) NOT NULL;

CREATE UNIQUE INDEX uk_audit_event_event_id ON audit_event(event_id);
CREATE INDEX idx_audit_session_occurred ON audit_event(session_id, occurred_at);
CREATE INDEX idx_audit_type_occurred ON audit_event(event_type, occurred_at);
CREATE INDEX idx_audit_model_call ON audit_event(model_call_id);
CREATE INDEX idx_audit_sql_attempt ON audit_event(sql_attempt_id);
CREATE INDEX idx_audit_candidate ON audit_event(evaluation_candidate, occurred_at);

-- 当前反馈是可更新投影；每次变化的完整历史仍只追加到 audit_event。
CREATE TABLE IF NOT EXISTS quality_feedback_current (
    message_id BIGINT PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(36),
    user_id BIGINT NOT NULL,
    feedback_code VARCHAR(8) NOT NULL,
    reason_code VARCHAR(64),
    comment TEXT,
    version BIGINT NOT NULL DEFAULT 1,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_quality_feedback_user_updated (user_id, updated_at),
    KEY idx_quality_feedback_task (task_id, updated_at),
    CONSTRAINT chk_quality_feedback_code CHECK (feedback_code IN ('LIKE', 'DISLIKE', 'NONE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='F维护的用户当前反馈投影';

-- 平滑接管 V8 已保存的反馈状态，不删除旧列，待兼容期结束后再另行迁移。
INSERT INTO quality_feedback_current(message_id, session_id, task_id, user_id, feedback_code, created_at, updated_at)
SELECT m.message_id, m.session_id, m.task_id, s.user_id, m.feedback_code,
       COALESCE(m.feedback_updated_at, m.created_at), COALESCE(m.feedback_updated_at, m.updated_at)
FROM conversation_message m
JOIN conversation_session s ON s.session_id = m.session_id
WHERE m.feedback_code IN ('LIKE', 'DISLIKE')
ON DUPLICATE KEY UPDATE
    feedback_code = VALUES(feedback_code),
    updated_at = VALUES(updated_at);
