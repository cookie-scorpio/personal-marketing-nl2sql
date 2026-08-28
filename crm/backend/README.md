# CRM 后端

本目录是个金营销 NL2SQL 平台的 Spring Boot v1.3 后端。登录、自然语言解析、SQL规划、查询执行和历史记录位于同一进程，各模块按业务包隔离，通过 Java 接口直接调用。

v1.3增加带类型的身份补充、明确的SQL权限诊断、DeepSeek原生SQL规划工具、三类文件日志、会话逻辑删除、消息导航目录及更丰富的模拟姓名。自由统计由模型生成SQL，最终执行仍经过账号权限/客户/只读校验和风险确认。完整字段与接口见[实施说明](../../docs/v1.3实施说明与接口数据字典.md)，新增V6/V7/V8迁移；V7会更新模拟姓名，必须先备份，不能对真实姓名库直接应用。

`mvn test`默认不连接数据库。使用真实MySQL测试时必须按[隔离库评测说明](../../scripts/evaluation/README.md)显式覆盖数据源，不能在用户库直接开启v11.mysql；该组测试不调用真实模型，会保留模拟任务与审计记录。

## 已实现功能

- V8恢复旧任务已保存的助手结果；会话按创建时间稳定倒序、消息按时间与编号分页，回复评价持久化并校验所有者。
- 本地数据库账号登录、BCrypt密码校验和JWT签发。
- 客户经理、团队负责人、机构负责人三级数据范围。
- 客户筛选、交易分析、产品持有、营销活动四类语义解析。
- 缺少时间或业务主题时主动反问，条件冲突时要求用户明确最终条件。
- 高频明确场景优先使用规则；自由问题通过DeepSeek V4 Flash生成结构化JSON查询计划。
- 从MySQL业务术语表加载口径；模型低置信度、缺失条件或矛盾时主动反问。
- 固定模板生成SQL，命名参数绑定，执行前校验只读、单语句、表白名单和结果上限。
- MySQL异步查询、SSE阶段与状态恢复、潜在高成本查询确认、图表描述、基础分析和历史记录。
- MySQL保存会话、消息、上下文和事件；Redis加速幂等索引，不可用时仍由MySQL保证去重。
- Flyway数据库迁移、操作审计和Actuator健康检查。

## 目录说明

```text
src/main/java/com/boc/nl2sql/
├── access              HTTP接入、登录、JWT和Spring Security
├── authorization       用户身份、角色和强制数据范围
├── conversation        查询任务、状态机、反问和确认接口
├── nl2sql              语义对象、规则解析和完备性校验
├── model               Mock、DeepSeek和Qwen模型适配器
├── knowledge           业务术语读取、BCG-E3与Milvus外置接口位置
├── execution           SQL规划、安全校验、MySQL执行和结果封装
├── history             用户可见的查询历史
├── audit               不随历史删除的操作审计
└── common              统一响应、异常、请求标识和公共配置

src/main/resources/
├── application.yml                    默认环境变量配置
├── application-local.example.yml      本地配置示例
├── prompts/nl2sql-system.txt           意图识别、澄清、JSON查询计划规则
├── prompts/nl2sql-schema.txt           数据字典、字段关系与业务能力边界
└── db/migration/                       Flyway表结构和术语种子
```

关键代码处的注释主要解释安全边界、状态流转、降级原因和后续替换点，普通Getter等不重复添加无意义注释。

## 调用链路

```text
POST /api/v1/queries
  → SecurityFilterChain校验JWT
  → QueryApplicationService创建任务
  → QueryTaskProcessor异步编排
  → ModelGateway优先规则，必要时调用DeepSeek
  → CompletenessValidator检查缺失与冲突
  → SqlPlanner注入DataScope并生成受控SQL
  → SqlSafetyValidator执行只读和白名单校验
  → QueryExecutionGateway查询MySQL
  → ResultAssembler整理指标、图表、表格和基础分析
  → HistoryService与AuditService保存摘要
  → 前端订阅SSE，断线续传，必要时读取状态恢复
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

仅演示规则问题时可以保持`MODEL_PROVIDER=mock`。演示自由提问时填写：

```text
MODEL_PROVIDER=deepseek
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_API_KEY=填写API密钥
DEEPSEEK_MODEL=deepseek-v4-flash
```

`deepseek`已实现真实HTTP调用；不会发送查询结果或客户明细，只发送问题、表结构、术语和模拟数据范围。Qwen仍是历史预留适配位置，不建议在本期启用。

对话默认开启思考模式，可在页面按任务关闭；首次输出上限16384 token，空响应或截断时最多重试一次、上限32768 token。可在`application-local.yml`已有的`app.model.deepseek`下补充：

```yaml
thinking-enabled: true
max-tokens: 16384
retry-max-tokens: 32768
read-timeout-seconds: 120
tools-enabled: true
max-tool-rounds: 3
```

已有local配置若仍指定旧的token和超时值，需要手工调整非敏感项。以上配置与`base-url`、`api-key`、`model`同级，不要重复声明`app`节点。默认值已经生效，不必改动现有密钥。若显式启用思考，需要为思考与最终JSON一起预留足够的输出额度。

提示词原先位于`DeepSeekModelAdapter`常量中，现在由`Nl2SqlPrompts`加载`prompts/`中的UTF-8文本，再加入当前日期、数据库术语、当前账号数据范围和用户问题。修改提示词后需重新构建/重启；不支持页面在线编辑。

## 构建与运行

要求JDK 17和Maven 3.9.16：

```powershell
cd D:\code\boc\0824nl2sql\crm\backend
$env:JAVA_HOME = "D:\tools\jdk17" # 按本机 JDK 17 安装位置调整
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn test
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run
```

默认接口地址为 `http://127.0.0.1:8080`，健康检查为 `http://127.0.0.1:8080/actuator/health`。

### 启动失败排查

`Process terminated with exit code: 1` 只是 Maven 对后端退出的汇总，实际原因在它之前的 `APPLICATION FAILED TO START` 或最后一条 `Caused by` 中。

- 日志显示 `No active profile set` 且 MySQL 返回 1045：确认在执行 Maven 的同一个 PowerShell 窗口设置了 `SPRING_PROFILES_ACTIVE`。也可直接执行 `mvn "-Dspring-boot.run.profiles=local" spring-boot:run`，不依赖窗口中的 profile 环境变量。
- 日志显示 `Port 8080 was already in use`：先检查监听进程。Windows 上即使没有监听进程，系统保留的端口也可能无法绑定，可执行 `netsh interface ipv4 show excludedportrange protocol=tcp` 检查。

本机排查时发现 8080、8081 均位于 Windows 保留的 7998–8097 区间，已在被 Git 忽略的 `application-local.yml` 中设置 `server.port: ${SERVER_PORT:18080}`，本地健康地址改为 `http://127.0.0.1:18080/actuator/health`。没有修改默认环境或系统保留规则；如果当前终端设置过 `SERVER_PORT`，它仍会覆盖本地默认端口。

更换后端端口后，在 `crm/frontend/.env.development.local` 设置 `API_PROXY_TARGET=http://127.0.0.1:18080` 并重启 `npm run dev`。前端仍使用相对的 `/api` 路径，不需要改成跨域请求。本机这两个本地配置已同步。

从仓库根目录执行`pwsh -File scripts/verify-model.ps1`可验收渠道转化比较、按月交易趋势和规则快速查询。前两条会调用真实模型API并消耗额度；该脚本不会自动确认高风险SQL。

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
- `POST /api/v1/queries`：提交自然语言问题，必须提供稳定的`Idempotency-Key`，支持`thinking_enabled`。
- `GET /api/v1/queries/{taskId}/events`：SSE，支持`Last-Event-ID`。
- `GET /api/v1/conversations`及`/{sessionId}`：会话列表与消息恢复。
- `DELETE /api/v1/conversations/{sessionId}`：逻辑删除会话，活动任务必须先结束。
- `GET /api/v1/conversations/{sessionId}/anchors`：分页读取用户输入目录。
- `POST /api/v1/queries/{taskId}/cancel`：取消当前任务。
- `GET /api/v1/queries/{taskId}/status`：查询任务状态和结果。
- `POST /api/v1/conversations/{sessionId}/messages`：补充或澄清条件。
- `POST /api/v1/queries/{taskId}/confirmations`：确认高范围查询。
- `GET /api/v1/query-history`：查询本人历史记录。
- `DELETE /api/v1/query-history/{historyId}`：删除本人可见历史，不删除审计日志。

## 注意事项

- 当前及后续SQL执行统一使用MySQL，不使用Spark SQL或Hive。
- BCG-E3和Milvus不进入本期运行环境，仅保留`EmbeddingClient`、`VectorStore`和外部配置。
- 本版只使用单节点Redis 7.x，不要求Redis Cluster。
- 生产环境必须替换默认JWT密钥、演示账号和数据库密码，并由统一认证中心接管登录。
- conversation.log保存基础脱敏后的用户输入和助手阶段；application.log保存运行诊断。不记录完整Prompt、查询明细、思考正文或密码，但自由文本脱敏不等于生产级隐私治理。业务SQL及脱敏绑定参数写入`logs/sql-review.log`，由`SQL_REVIEW_LOG_DIR`修改目录；按20MB/每天滚动，保留14天，上限1GB。日志仅供授权人员核查虚构数据。

详细字段、接口示例、当前限制和升级方式见 [v1.3实施说明](../../docs/v1.3实施说明与接口数据字典.md)。
