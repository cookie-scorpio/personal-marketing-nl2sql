-- V12: 两人对比确认（MULTI_CUSTOMER_CONFIRM）。保存指代队列与逐位确认状态：
-- [{"referent":"第1位：王先生","keyword":"王","customerId":null}, ...]
-- 全部确认后由服务端收敛为 customer_ids_json（IN 名单，复用 v1.7 校验）。
ALTER TABLE query_task ADD COLUMN multi_customers_json JSON NULL;
