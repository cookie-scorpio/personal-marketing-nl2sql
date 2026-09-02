-- 会话与消息已经成为唯一的用户历史入口，移除不再维护的兼容查询历史投影。
DROP TABLE IF EXISTS query_history;
