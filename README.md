# 个金营销 NL2SQL 平台

本项目面向个人金融营销场景，让业务人员通过自然语言查询模拟客户、交易、产品持有和营销活动数据，并以指标、图表、表格和基础分析理解结果。

当前为 v1.1：在登录、规则与 DeepSeek 混合识别、信息补充、SQL 风险确认、MySQL 查询和历史记录基础上，新增查询取消、执行超时、最多两次 SQL 修复与模板降级、多指标多图展示，以及客户时间口径确认。高频问题优先走规则，自由问题在配置 DeepSeek API 后使用大模型。Milvus 与 BCG-E3 暂只保留外置接口。

完整的实现范围、未实现项、数据库字段中文释义、API 示例和验证记录见 [v1.1 实施说明与接口数据字典](docs/v1.1实施说明与接口数据字典.md)。[v1.0 文档](docs/v1.0实施说明与接口数据字典.md)保留作历史参考。

## 主要内容

- `crm/`：客户关系管理系统，包含前端和 Spring Boot 后端。
- `data-product/`：保留 MySQL 数据服务、元数据与 SQL 资产的后续扩展目录。
- `llm-service/`：保留模型服务的后续扩展目录，当前混合识别和模型调用已在 Java 后端实现。
- `contracts/`：跨系统使用的 OpenAPI、事件和数据结构定义。
- `deploy/`：容器、环境和部署说明，不保存密码、令牌等敏感信息。
- `docs/`：架构、开发、接口和决策记录。

完整目录说明见 [仓库架构说明](docs/architecture/repository-structure.md)，Gitee 创建和团队配置见 [Gitee 仓库创建指南](docs/development/gitee-setup.md)。

## 开始开发

1. 阅读 [协作规范](CONTRIBUTING.md)。
2. 从 `main` 创建短期功能分支，例如 `feat/crm-customer-search`。
3. 只修改自己负责的业务目录；跨系统接口先更新 `contracts/`。
4. 本地完成测试后推送分支，并通过 Pull Request 合并到 `main`。

前后端目录已提供安装、配置、运行和测试命令。

## v1.1 本地运行

1. 按[本地基础服务说明](deploy/local/README.md)启动MySQL 8.4和Redis 7.x。
2. 按[后端说明](crm/backend/README.md)配置密码并启动Spring Boot，Flyway会自动创建表和演示账号。
3. 按[虚构数据生成说明](scripts/mock-data/README.md)生成1万名客户和相关营销数据。
4. 按[前端说明](crm/frontend/README.md)启动Vue开发服务器。

浏览器访问`http://127.0.0.1:5173`，可使用`manager01`、`leader01`或`director01`登录，演示密码为`Demo@123`。

SQL默认执行超时为60秒，通过`QUERY_EXECUTION_TIMEOUT_SECONDS`配置。升级时需要部署同版本前后端，Flyway自动执行V4；先停止旧版本任务，避免旧进程覆盖新版取消状态。取消或超时后不会继续修复或执行模板。

## 配置与运行

当前可执行应用位于 `crm/`，模型编排与 MySQL 查询均在 Spring Boot 内按模块实现；其他目录保留扩展边界：

- CRM 前端：在 `crm/frontend/` 中维护 `package.json` 和环境变量示例。
- CRM 后端：在 `crm/backend/` 中维护 Maven 工程和 Spring Boot 配置。
- 数据查询：当前直接使用 MySQL，不需要 Spark、Hive 或额外数据服务。
- 大模型接入：配置 Java 后端的 DeepSeek 地址、模型和密钥，不需要独立 Python 服务。

本地配置使用 `.env.example`、`application-local.example.yml` 等示例文件。真实密钥、数据库密码、客户数据和生产配置不得提交到仓库。

## 部署

先启动 MySQL 和 Redis，再部署后端 Maven 生成的可执行 JAR，最后将前端 `npm run build` 生成的 `dist/` 交给静态服务器。静态服务器需将 `/api` 和 `/actuator/health` 反向代理到后端。密钥和环境差异通过外部配置注入。具体命令见前后端说明。

## 注意事项

- 当前面向模拟数据，后端限制查询为只读 SQL，并校验账号数据范围；不应直接用于生产客户数据。生产部署还需独立只读查询账号、完整 SQL 语法树校验和更严格的权限控制。
- 日志、测试样例、提示词和截图不得包含真实客户敏感信息。
- `main` 和 `release/*` 应设置为保护分支，禁止开发人员直接推送。
- 生产故障修复使用 `hotfix/*`，合并后打版本标签并保留审计记录。

## 许可

本项目为内部项目，代码、数据和文档的使用范围遵循组织内部管理制度。未经授权不得对外发布或分发。
