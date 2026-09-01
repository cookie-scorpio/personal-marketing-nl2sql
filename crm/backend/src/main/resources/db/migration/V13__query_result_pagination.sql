-- 查询结果分页参数随异步任务持久化；默认值保持旧调用方一次返回100行的行为。
ALTER TABLE query_task
    ADD COLUMN page_no INT NOT NULL DEFAULT 1,
    ADD COLUMN page_size INT NOT NULL DEFAULT 100,
    ADD COLUMN page_offset BIGINT NOT NULL DEFAULT 0;
