CREATE TABLE query_sql_repair (
    repair_id BIGINT PRIMARY KEY AUTO_INCREMENT,
    task_id VARCHAR(36) NOT NULL,
    attempt_no INT NOT NULL,
    trigger_phase VARCHAR(24) NOT NULL COMMENT 'VALIDATION、EXECUTION或RESULT_REVIEW',
    status_code VARCHAR(24) NOT NULL COMMENT 'STARTED、GENERATED、APPLIED、REJECTED或MODEL_FAILED',
    original_sql TEXT NOT NULL,
    failure_reason VARCHAR(1000) NOT NULL COMMENT '脱敏后的校验、执行或结构复核原因',
    repair_reason VARCHAR(1000) NOT NULL COMMENT '面向用户和审计的修复原因',
    repaired_sql TEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_query_repair_attempt(task_id, attempt_no),
    KEY idx_query_repair_task_created(task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='v1.4 SQL有限自修复轨迹';
