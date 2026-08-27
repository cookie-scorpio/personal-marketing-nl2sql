ALTER TABLE query_task
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0 COMMENT '状态乐观锁版本',
    ADD COLUMN repair_attempts INT NOT NULL DEFAULT 0 COMMENT '已消耗的SQL修复调用次数，最多2次',
    ADD COLUMN column_hints_json JSON NULL COMMENT '计划结果字段的角色、单位与聚合口径',
    ADD COLUMN fallback_json JSON NULL COMMENT '模板降级原因与模板编号';
