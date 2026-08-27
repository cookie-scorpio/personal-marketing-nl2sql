ALTER TABLE query_task
    ADD COLUMN interpretation_source VARCHAR(24) NULL COMMENT '意图识别来源：RULE或DEEPSEEK' AFTER intent_code,
    ADD COLUMN interpretation_confidence DECIMAL(5,4) NULL COMMENT '意图识别置信度，范围0到1' AFTER interpretation_source,
    ADD COLUMN preferred_display VARCHAR(16) NULL COMMENT '建议展示形式' AFTER interpretation_confidence,
    ADD COLUMN risk_json JSON NULL COMMENT 'SQL风险等级与确认原因' AFTER confirmation_token;

ALTER TABLE query_task COMMENT = 'V1.0 NL2SQL异步查询任务';
