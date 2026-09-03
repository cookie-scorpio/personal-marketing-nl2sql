-- 评测维度扩展：条目补金标意图，运行明细记录实际意图与解释来源，
-- 支撑意图识别准确率、澄清质量等评测维度与后续优化洞察。
ALTER TABLE eval_dataset_item
 ADD COLUMN intent_code VARCHAR(32) NULL COMMENT '金标意图，评测意图识别准确率时与重放判定对比' AFTER note;

ALTER TABLE eval_run_item
 ADD COLUMN intent_code VARCHAR(32) NULL COMMENT '重放时系统判定的意图编码' AFTER failure_stage,
 ADD COLUMN interpretation_source VARCHAR(32) NULL COMMENT '查询计划来源：MODEL/RULE/TEMPLATE_FALLBACK' AFTER intent_code;
