-- V10: 补齐核心查询路径缺失的索引。
-- 1) fct_customer_marketing.contact_time：营销类问题按触达时间过滤（提示词口径），此前为全表扫描。
-- 2) fct_product_holding.snapshot_date：'取最新快照'类查询此前只能走客户索引后过滤。
CREATE INDEX idx_marketing_contact_time ON fct_customer_marketing (contact_time);
CREATE INDEX idx_holding_snapshot ON fct_product_holding (snapshot_date);
