ALTER TABLE conversation_session ADD COLUMN deleted_at DATETIME(3) NULL COMMENT '逻辑删除时间，保留审计';
CREATE INDEX idx_session_visible ON conversation_session(user_id,deleted_at,updated_at);
