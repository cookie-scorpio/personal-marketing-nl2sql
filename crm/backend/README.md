# CRM 后端

本目录是个金营销 NL2SQL 平台的 Spring Boot 后端。MVP 将登录接入、权限控制、自然语言解析、SQL规划、查询执行和历史记录放在同一个进程中，各模块按文件夹隔离，通过 Java 接口直接调用。

## 已实现功能

- 本地数据库账号登录、BCrypt密码校验和JWT签发。
- 客户经理、团队负责人、机构负责人三级数据范围。
- 客户筛选、交易分析、产品持有、营销活动四类语义解析。
- 缺少时间或业务主题时主动反问，条件冲突时要求用户明确最终条件。
- Mock、DeepSeek和Qwen统一模型适配接口；MVP默认使用确定性Mock。
- 固定模板生成SQL，命名参数绑定，执行前校验只读、单语句、表白名单和结果上限。
- MySQL异步查询、任务状态轮询、全量查询二次确认、结果封装和历史记录。
- Redis短期会话索引；Redis不可用时仅在本地开发环境降级为进程内缓存。
- Flyway数据库迁移、操作审计和Actuator健康检查。

## 目录说明

```text
src/main/java/com/boc/nl2sql/
├── access              HTTP接入、登录、JWT和Spring Security
├── authorization       用户身份、角色和强制数据范围
├── conversation        查询任务、状态机、反问和确认接口
├── nl2sql              语义对象、规则解析和完备性校验
├── model               Mock、DeepSeek和Qwen模型适配器
├── knowledge           BGE-M3与Milvus的后续接口位置
├── execution           SQL规划、安全校验、MySQL执行和结果封装
├── history             用户可见的查询历史
├── audit               不随历史删除的操作审计
└── common              统一响应、异常、请求标识和公共配置

src/main/resources/
├── application.yml                    默认环境变量配置
├── application-local.example.yml      本地配置示例
└── db/migration/                       Flyway表结构和术语种子
```

关键代码处的注释主要解释安全边界、状态流转、降级原因和后续替换点，普通Getter等不重复添加无意义注释。

## 调用链路

```text
POST /api/v1/queries
  → SecurityFilterChain校验JWT
  → QueryApplicationService创建任务
  → QueryTaskProcessor异步编排
  → ModelGateway选择Mock/DeepSeek/Qwen
  → CompletenessValidator检查缺失与冲突
  → SqlPlanner注入DataScope并生成受控SQL
  → SqlSafetyValidator执行只读和白名单校验
  → QueryExecutionGateway查询MySQL
  → ResultAssembler整理已脱敏结果
  → HistoryService与AuditService保存摘要
  → 前端轮询任务状态并展示结果
```

任务处于 `ASKING` 时，前端调用 `/api/v1/conversations/{sessionId}/messages` 补充条件；处于 `CONFIRMING` 时，调用 `/api/v1/queries/{taskId}/confirmations` 确认或取消。

## 配置

先启动 [本地基础服务](../../deploy/local/README.md)，复制本地配置示例并填写与 `deploy/local/.env` 相同的密码。`application-local.yml` 已被 Git 忽略：

```powershell
Copy-Item src\main\resources\application-local.example.yml src\main\resources\application-local.yml
```

也可以不创建本地配置文件，改为在当前终端设置环境变量：

```powershell
$env:MYSQL_PASSWORD = "本地应用数据库密码"
$env:REDIS_PASSWORD = "本地Redis密码"
$env:JWT_SECRET = "至少32字节的本地随机字符串"
```

模型API参数当前可以保持为空：

```text
MODEL_PROVIDER=mock
DEEPSEEK_BASE_URL=
DEEPSEEK_API_KEY=
QWEN_BASE_URL=
QWEN_API_KEY=
```

选择 `deepseek` 或 `qwen` 时，MVP仍由规则模拟适配器返回受控语义对象，不会向外部发送数据。

## 构建与运行

要求JDK 17和Maven 3.9.16：

```powershell
cd D:\code\boc\0824nl2sql\crm\backend
mvn test
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run
```

默认接口地址为 `http://127.0.0.1:8080`，健康检查为 `http://127.0.0.1:8080/actuator/health`。

首次启动会由Flyway创建表，并写入三个数据库演示账号：

| 用户名 | 角色 | 数据范围 | 密码 |
|---|---|---|---|
| `manager01` | 客户经理 | `M0001`负责客户 | `Demo@123` |
| `leader01` | 团队负责人 | `B001`网点 | `Demo@123` |
| `director01` | 机构负责人 | `EAST`区域 | `Demo@123` |

随后按 [虚构数据生成说明](../../scripts/mock-data/README.md) 写入演示业务数据。

## 主要接口

- `POST /api/v1/auth/login`：账号密码登录。
- `GET /api/v1/auth/me`：读取当前用户和数据范围。
- `POST /api/v1/queries`：提交自然语言问题。
- `GET /api/v1/queries/{taskId}/status`：查询任务状态和结果。
- `POST /api/v1/conversations/{sessionId}/messages`：补充或澄清条件。
- `POST /api/v1/queries/{taskId}/confirmations`：确认高范围查询。
- `GET /api/v1/query-history`：查询本人历史记录。
- `DELETE /api/v1/query-history/{historyId}`：删除本人可见历史，不删除审计日志。

## 注意事项

- 当前SQL执行层使用MySQL模拟分析库，后续通过`QueryExecutionGateway`替换为Spark/Hive。
- BGE-M3和Milvus不进入本期运行环境，仅保留`EmbeddingClient`与`VectorStore`接口。
- 生产环境必须替换默认JWT密钥、演示账号和数据库密码，并由统一认证中心接管登录。
- 不要把完整Prompt、客户明细、实际SQL参数或密码写入日志。
