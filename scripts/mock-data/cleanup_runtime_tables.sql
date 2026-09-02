-- 运行时表清理：仅用于演示环境数据重建（配合 scripts/mock-data/generate_data.py --reset 使用）。
-- 清空对话、任务、修复轨迹与审计，保留登录账号、Flyway 历史与业务维表/事实表。
-- 生产环境禁止执行；执行前确认已连接 pf_nl2sql 且已备份。
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE conversation_message;
TRUNCATE TABLE conversation_session;
TRUNCATE TABLE query_task_event;
TRUNCATE TABLE query_sql_repair;
TRUNCATE TABLE query_task;
TRUNCATE TABLE audit_event;
SET FOREIGN_KEY_CHECKS = 1;
SELECT 'runtime tables cleared' AS result;
