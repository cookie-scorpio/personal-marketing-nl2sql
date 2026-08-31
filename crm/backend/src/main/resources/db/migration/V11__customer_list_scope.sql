-- V11: @客户名单批量查询（方案甲）。任务保存服务端已核验的客户编号集合；
-- 生成SQL必须以 customer_id IN (名单) 表达，校验器按集合等值证明单客/名单约束。
ALTER TABLE query_task ADD COLUMN customer_ids_json JSON NULL;
