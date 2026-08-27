# OpenAPI

跨系统REST API定义放置在本目录。

- `crm-nl2sql-mvp-v1.yaml`：前端与Spring Boot之间的MVP接口，包括登录、查询提交、状态、补充、确认和历史记录。

接口调整应先更新OpenAPI，再同步后端DTO与前端TypeScript类型。当前MVP采用统一响应结构，JSON字段使用`snake_case`。
