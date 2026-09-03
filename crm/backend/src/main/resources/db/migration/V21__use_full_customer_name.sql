-- 姓名展示列切换：RAG 示例 SQL 中 customer_name_masked 改为 customer_name，
-- 与 SqlAstValidator 白名单、Prompt 模板和内置模板 SQL 保持一致。
-- 向量索引无需重建：EmbeddingIndexService 基于 question_text 建向量，sql_text 变更不影响；
-- RetrievalAugmentor 每次查询实时读 sql_text，改后立即生效。

UPDATE nl2sql_example
   SET sql_text = REPLACE(sql_text, 'customer_name_masked', 'customer_name'),
       version_no = '1.1'
 WHERE example_code = 'EX_TOP_ASSET_CUSTOMER';
