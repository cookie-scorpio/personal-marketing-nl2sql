# OpenAPI

跨系统REST API定义放置在本目录。

- `crm-nl2sql-mvp-v1.yaml`：当前 v1.5 接口，包括登录、查询提交、状态、补充、确认、取消、会话、受限客户筛选、修复记录、降级结果和多图字段；路径继续使用 `/api/v1`。

接口调整应先更新OpenAPI，再同步后端DTO与前端TypeScript类型。当前MVP采用统一响应结构，JSON字段使用`snake_case`。
