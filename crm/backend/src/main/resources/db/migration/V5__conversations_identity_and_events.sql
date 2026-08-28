-- v1.2：仅演示数据。既有脱敏姓名不可反向还原，完整姓名由独立脚本生成。
ALTER TABLE dim_customer ADD COLUMN customer_name VARCHAR(64) NULL COMMENT '虚构完整姓名，仅客户定位使用';
CREATE INDEX idx_customer_full_name ON dim_customer(customer_name);
-- 明确生成新的虚构姓名，不声称从脱敏字段恢复原姓名；不改变客户编号、资产与关联。
UPDATE dim_customer SET customer_name=CONCAT(LEFT(customer_name_masked,1),
 ELT(1+MOD(CRC32(customer_id),8),'明','华','晓明','文博','思远','小雨','嘉宁','安'))
 WHERE customer_name IS NULL;
UPDATE dim_customer SET customer_name_masked=CONCAT(LEFT(customer_name,1),REPEAT('*',GREATEST(CHAR_LENGTH(customer_name)-1,1))) WHERE customer_name IS NOT NULL;

CREATE TABLE conversation_session (
 session_id VARCHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 title VARCHAR(160) NOT NULL,
 active_task_id VARCHAR(36) NULL,
 context_json JSON NULL,
 state_version BIGINT NOT NULL DEFAULT 0,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 KEY idx_session_user_updated(user_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE query_task
 MODIFY COLUMN merged_query_text TEXT NOT NULL,
 ADD COLUMN thinking_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 ADD COLUMN idempotency_key VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
 ADD COLUMN request_hash CHAR(64) NULL,
 ADD COLUMN context_json JSON NULL,
 ADD COLUMN resolved_customer_id VARCHAR(32) NULL,
 ADD COLUMN display_query TEXT NULL,
 ADD UNIQUE KEY uk_query_idempotency(user_id, idempotency_key);

CREATE TABLE conversation_message (
 message_id BIGINT PRIMARY KEY AUTO_INCREMENT,
 session_id VARCHAR(36) NOT NULL,
 task_id VARCHAR(36) NOT NULL,
 role_code VARCHAR(16) NOT NULL,
 message_key VARCHAR(100) NOT NULL,
 content TEXT NOT NULL,
 payload_json JSON NULL,
 created_at DATETIME(3) NOT NULL,
 updated_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_message_operation(task_id, message_key),
 KEY idx_message_session(session_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE query_task_event (
 event_id BIGINT PRIMARY KEY AUTO_INCREMENT,
 task_id VARCHAR(36) NOT NULL,
 state_version BIGINT NOT NULL,
 payload_json JSON NOT NULL,
 created_at DATETIME(3) NOT NULL,
 UNIQUE KEY uk_task_event_version(task_id, state_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 历史任务保留为可打开的会话，不臆造已丢失的中间消息或上下文。
INSERT INTO conversation_session(session_id,user_id,title,created_at,updated_at)
 SELECT session_id, MIN(user_id), LEFT(MIN(query_text),160), MIN(created_at), MAX(updated_at)
 FROM query_task GROUP BY session_id HAVING COUNT(DISTINCT user_id)=1;
INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,created_at,updated_at)
 SELECT q.session_id,q.task_id,'USER','query',q.query_text,q.created_at,q.created_at
 FROM query_task q JOIN conversation_session s ON s.session_id=q.session_id AND s.user_id=q.user_id;
