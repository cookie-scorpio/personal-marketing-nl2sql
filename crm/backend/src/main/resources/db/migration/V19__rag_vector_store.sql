-- 已发布迁移不可回写：本迁移新增检索增强（RAG）所需的向量持久化表与标准示例表。
-- 向量由后端调用DashScope兼容 /embeddings 端点生成，存MySQL并在进程内做余弦检索；
-- Milvus等外置向量库的接入保留在VectorStore接口之后，后续迁移再扩展。

CREATE TABLE IF NOT EXISTS vector_embedding (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_type VARCHAR(32) NOT NULL,
    ref_id VARCHAR(64) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    content_text TEXT NOT NULL,
    embedding JSON NOT NULL,
    dim INT NOT NULL,
    model VARCHAR(64) NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_vector_biz_ref (biz_type, ref_id),
    KEY idx_vector_model (model)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='检索增强向量持久化；同一(biz_type,ref_id)只保留最新向量';

-- 标准问题到SQL的示例对，检索增强按问题相似度取top-k注入提示词。
-- sql_text中的日期使用占位符，渲染时替换为运行时真实日期，避免示例随时间过期：
--   {today} 当天；{today-90} 90天前；{year-start} 当年1月1日；{snapshot-date} 最新持有快照日期。
-- 示例中的数据范围条件（如 c.region_code = 'EAST'）仅为写法示意，模型生成时必须替换为服务端注入的实时范围。
CREATE TABLE IF NOT EXISTS nl2sql_example (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    example_code VARCHAR(64) NOT NULL,
    question_text VARCHAR(512) NOT NULL,
    sql_text TEXT NOT NULL,
    intent_code VARCHAR(32) NOT NULL DEFAULT 'GENERIC_ANALYSIS',
    notes VARCHAR(255) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version_no VARCHAR(16) NOT NULL DEFAULT '1.0',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_nl2sql_example_code (example_code),
    KEY idx_nl2sql_example_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='NL2SQL标准示例；示例SQL只演示写法，范围条件运行时以服务端注入为准';

INSERT INTO nl2sql_example(example_code, question_text, sql_text, intent_code, notes) VALUES
    ('EX_CUSTOMER_AGE_BAND', '本区域各年龄段客户分别有多少人',
     'SELECT c.age_band_code, COUNT(*) AS customer_count FROM dim_customer c WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' GROUP BY c.age_band_code ORDER BY customer_count DESC, c.age_band_code',
     'CUSTOMER_FILTER', '单表分组计数；展示别名与排序写法'),
    ('EX_HNW_COUNT_AVG', '高净值客户有多少人？平均资产多少',
     'SELECT COUNT(*) AS hnw_customer_count, ROUND(AVG(c.total_asset_amount) / 10000, 2) AS avg_asset_wan FROM dim_customer c WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND (c.total_asset_amount >= 1000000 OR c.customer_level_code = ''PLATINUM'')',
     'CUSTOMER_FILTER', '业务口径组合条件；金额换算万元'),
    ('EX_TX_TYPE_90D', '近90天各交易类型的金额构成',
     'SELECT t.transaction_type_code, ROUND(SUM(t.amount_cny) / 10000, 2) AS amount_wan FROM fct_transaction t INNER JOIN dim_customer c ON t.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND t.status_code = ''SUCCESS'' AND t.transaction_time >= ''{today-90}'' GROUP BY t.transaction_type_code ORDER BY amount_wan DESC',
     'TRANSACTION_ANALYSIS', '交易统计默认只统计SUCCESS；时间占位符'),
    ('EX_TX_DAILY_TREND', '看下近30天每天的交易金额走势',
     'SELECT t.transaction_date, ROUND(SUM(t.amount_cny) / 10000, 2) AS daily_amount_wan FROM fct_transaction t INNER JOIN dim_customer c ON t.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND t.status_code = ''SUCCESS'' AND t.transaction_date >= ''{today-30}'' GROUP BY t.transaction_date ORDER BY t.transaction_date',
     'TRANSACTION_ANALYSIS', '按日趋势，按日期列排序'),
    ('EX_HOLDING_BY_CATEGORY', '各类产品的持仓市值和持有客户数',
     'SELECT h.product_category_code, ROUND(SUM(h.market_value_amount) / 10000, 2) AS market_value_wan, COUNT(DISTINCT h.customer_id) AS customer_count FROM fct_product_holding h INNER JOIN dim_customer c ON h.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' GROUP BY h.product_category_code ORDER BY market_value_wan DESC',
     'PRODUCT_HOLDING', '人数用COUNT(DISTINCT customer_id)去重'),
    ('EX_WEALTH_AVG_HOLDING', '理财客户平均持仓市值是多少',
     'WITH per_customer AS (SELECT h.customer_id, SUM(h.market_value_amount) AS holding_value FROM fct_product_holding h INNER JOIN dim_customer c ON h.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND h.product_category_code = ''WEALTH'' GROUP BY h.customer_id) SELECT ROUND(AVG(holding_value) / 10000, 2) AS avg_holding_wan, COUNT(*) AS customer_count FROM per_customer',
     'PRODUCT_HOLDING', '先聚合到一人一行再求均值，避免重复计算'),
    ('EX_CAMPAIGN_CONVERSION', '今年以来各触达渠道的营销转化率',
     'SELECT m.contact_channel_code, COUNT(DISTINCT m.customer_id) AS contacted_customers, COUNT(DISTINCT CASE WHEN m.conversion_flag = 1 THEN m.customer_id END) AS converted_customers, ROUND(COUNT(DISTINCT CASE WHEN m.conversion_flag = 1 THEN m.customer_id END) * 100.0 / NULLIF(COUNT(DISTINCT m.customer_id), 0), 2) AS conversion_rate_pct FROM fct_customer_marketing m INNER JOIN dim_customer c ON m.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND m.contact_time >= ''{year-start}'' GROUP BY m.contact_channel_code ORDER BY contacted_customers DESC, m.contact_channel_code',
     'MARKETING_ANALYSIS', '转化率分子分母都按客户去重；NULLIF防除零'),
    ('EX_CAMPAIGN_REACH', '近半年每个营销活动触达和转化了多少客户',
     'SELECT p.campaign_name, COUNT(DISTINCT m.customer_id) AS contacted_customers, COUNT(DISTINCT CASE WHEN m.conversion_flag = 1 THEN m.customer_id END) AS converted_customers FROM fct_customer_marketing m INNER JOIN dim_marketing_campaign p ON m.campaign_id = p.campaign_id INNER JOIN dim_customer c ON m.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND m.contact_time >= ''{today-180}'' GROUP BY p.campaign_name ORDER BY contacted_customers DESC',
     'MARKETING_ANALYSIS', '活动维度关联dim_marketing_campaign'),
    ('EX_DEPOSIT_BAND', '客户存款市值分档分布',
     'WITH scoped AS (SELECT c.customer_id, COALESCE(SUM(h.market_value_amount), 0) AS deposit_amount FROM dim_customer c LEFT JOIN fct_product_holding h ON h.customer_id = c.customer_id AND h.product_category_code = ''DEPOSIT'' AND h.snapshot_date = ''{snapshot-date}'' WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' GROUP BY c.customer_id) SELECT CASE WHEN deposit_amount < 500000 THEN ''0-50万'' WHEN deposit_amount < 1000000 THEN ''50-100万'' WHEN deposit_amount < 3000000 THEN ''100-300万'' ELSE ''300万以上'' END AS deposit_band, COUNT(*) AS customer_count FROM scoped GROUP BY deposit_band ORDER BY deposit_band',
     'GENERIC_ANALYSIS', '左闭右开分档；无持有按0计入；快照日期占位符'),
    ('EX_TOP_ASSET_CUSTOMER', '资产最多的前10名客户',
     'SELECT customer_id, customer_name_masked, ROUND(total_asset_amount / 10000, 2) AS total_asset_wan FROM (SELECT c.customer_id, c.customer_name_masked, c.total_asset_amount, ROW_NUMBER() OVER (ORDER BY c.total_asset_amount DESC, c.customer_id) AS rn FROM dim_customer c WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'') ranked WHERE rn <= 10',
     'CUSTOMER_FILTER', 'Top N用ROW_NUMBER表达，禁止LIMIT'),
    ('EX_HOLDING_RISK_DIST', '不同风险等级产品的持仓市值分布',
     'SELECT h.risk_level_code, ROUND(SUM(h.market_value_amount) / 10000, 2) AS market_value_wan, COUNT(DISTINCT h.customer_id) AS customer_count FROM fct_product_holding h INNER JOIN dim_customer c ON h.customer_id = c.customer_id WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' GROUP BY h.risk_level_code ORDER BY h.risk_level_code',
     'PRODUCT_HOLDING', '风险等级维度分布，适合饼图'),
    ('EX_ASSET_DECLINE_COUNT', '近三个月资产下降明显的客户有多少',
     'SELECT c.age_band_code, COUNT(*) AS churn_risk_count FROM dim_customer c WHERE c.region_code = ''EAST'' AND c.status_code = ''ACTIVE'' AND c.asset_change_3m_rate < -0.05 GROUP BY c.age_band_code ORDER BY churn_risk_count DESC, c.age_band_code',
     'CUSTOMER_FILTER', '流失预警口径：近三个月资产变动率低于负向阈值')
ON DUPLICATE KEY UPDATE
    question_text = VALUES(question_text),
    sql_text = VALUES(sql_text),
    intent_code = VALUES(intent_code),
    notes = VALUES(notes),
    enabled = TRUE;
