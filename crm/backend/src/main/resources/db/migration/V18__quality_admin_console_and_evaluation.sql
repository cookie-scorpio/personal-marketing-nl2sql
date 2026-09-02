-- 质量审计后台：数据回流候选审核、评测集草稿/发布版本、评测运行与逐条明细。
-- 评测集采用“单草稿 + 发布版本”模型：同一时刻最多一份 DRAFT；发布后该行只读，
-- 并自动克隆出下一份 DRAFT 供继续维护，历次 PUBLISHED 版本永久保留。

CREATE TABLE eval_dataset (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(128) NOT NULL,
    description VARCHAR(500) NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
    version INT NOT NULL DEFAULT 0,
    item_count INT NOT NULL DEFAULT 0,
    published_at DATETIME(3) NULL,
    published_by BIGINT NULL,
    created_by BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_eval_dataset_status (status, updated_at),
    CONSTRAINT chk_eval_dataset_status CHECK (status IN ('DRAFT', 'PUBLISHED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='质量评测集：草稿可改，发布后不可变';

CREATE TABLE eval_dataset_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    source_event_id CHAR(36) NULL,
    source_task_id VARCHAR(36) NULL,
    question_text VARCHAR(2000) NOT NULL,
    expected_sql TEXT NOT NULL,
    note VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    KEY idx_eval_item_dataset (dataset_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测集条目：问题原文与人工审核的金标SQL';

-- 候选审计事件的审核结论，event_id 与 audit_event.evaluation_candidate=1 的记录一一对应。
CREATE TABLE eval_candidate_review (
    event_id CHAR(36) PRIMARY KEY,
    decision VARCHAR(16) NOT NULL,
    dataset_item_id BIGINT NULL,
    reviewed_by BIGINT NOT NULL,
    note VARCHAR(500) NULL,
    reviewed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_eval_candidate_decision CHECK (decision IN ('ACCEPTED', 'IGNORED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='候选审计事件的采纳/忽略结论';

CREATE TABLE eval_run (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id BIGINT NOT NULL,
    dataset_version INT NOT NULL,
    trigger_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    total_items INT NOT NULL DEFAULT 0,
    finished_items INT NOT NULL DEFAULT 0,
    passed_items INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000) NULL,
    triggered_by BIGINT NULL,
    started_at DATETIME(3) NULL,
    finished_at DATETIME(3) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_eval_run_dataset (dataset_id, id),
    KEY idx_eval_run_created (created_at),
    CONSTRAINT chk_eval_run_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测运行：发布自动触发或手动重跑';

CREATE TABLE eval_run_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id BIGINT NOT NULL,
    item_id BIGINT NOT NULL,
    question_text VARCHAR(2000) NOT NULL,
    expected_sql TEXT NULL,
    generated_sql TEXT NULL,
    execution_success BOOLEAN NOT NULL DEFAULT FALSE,
    sql_match BOOLEAN NULL,
    result_consistent BOOLEAN NULL,
    expected_rows INT NULL,
    actual_rows INT NULL,
    elapsed_ms BIGINT NULL,
    outcome VARCHAR(32) NOT NULL,
    failure_stage VARCHAR(16) NULL,
    error_message VARCHAR(1000) NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY idx_eval_run_item_run (run_id, id),
    CONSTRAINT chk_eval_run_item_stage CHECK (failure_stage IS NULL OR failure_stage IN ('INTERPRET', 'VALIDATE', 'EXECUTE', 'COMPARE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='评测运行明细：每条样本的逐维度评测结果';
