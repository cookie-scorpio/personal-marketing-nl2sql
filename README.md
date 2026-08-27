# 个金营销 NL2SQL 平台

本仓库用于统一管理个金营销 NL2SQL 项目的客户关系管理系统、大数据产品和大模型服务。三部分代码放在同一仓库中，接口、文档和发布规则保持一致，各应用仍可独立开发、测试和部署。

当前MVP已经实现可运行的前后端主链路：Vue工作台通过Spring Boot完成数据库账号登录、自然语言规则解析、信息补充、矛盾澄清、受控SQL生成、MySQL查询和历史记录。真实大模型、BGE-M3、Milvus与Spark/Hive保留替换接口，不进入本期运行环境。

## 主要内容

- `crm/`：客户关系管理系统，包含前端和 Spring Boot 后端。
- `data-product/`：大数据查询、元数据、批处理、实时任务及 SQL 资产。
- `llm-service/`：意图识别、信息完备性校验、澄清、NL2SQL、SQL 安全校验和效果评测。
- `contracts/`：跨系统使用的 OpenAPI、事件和数据结构定义。
- `deploy/`：容器、环境和部署说明，不保存密码、令牌等敏感信息。
- `docs/`：架构、开发、接口和决策记录。

完整目录说明见 [仓库架构说明](docs/architecture/repository-structure.md)，Gitee 创建和团队配置见 [Gitee 仓库创建指南](docs/development/gitee-setup.md)。

## 开始开发

1. 阅读 [协作规范](CONTRIBUTING.md)。
2. 从 `main` 创建短期功能分支，例如 `feat/crm-customer-search`。
3. 只修改自己负责的业务目录；跨系统接口先更新 `contracts/`。
4. 本地完成测试后推送分支，并通过 Pull Request 合并到 `main`。

各模块首次落代码时，应在对应目录补齐可执行的安装、配置、运行和测试命令。

## MVP本地运行

1. 按[本地基础服务说明](deploy/local/README.md)启动MySQL 8.4和Redis 7.x。
2. 按[后端说明](crm/backend/README.md)配置密码并启动Spring Boot，Flyway会自动创建表和演示账号。
3. 按[虚构数据生成说明](scripts/mock-data/README.md)生成1万名客户和相关营销数据。
4. 按[前端说明](crm/frontend/README.md)启动Vue开发服务器。

浏览器访问`http://127.0.0.1:5173`，可使用`manager01`、`leader01`或`director01`登录，演示密码为`Demo@123`。

## 配置与运行

三个系统独立维护构建文件和运行配置：

- CRM 前端：在 `crm/frontend/` 中维护 `package.json` 和环境变量示例。
- CRM 后端：在 `crm/backend/` 中维护 Maven 工程和 Spring Boot 配置。
- 大数据产品：每个可部署服务或任务目录独立维护构建文件。
- 大模型服务：在 `llm-service/` 中维护 Python 依赖、启动命令和模型配置示例。

本地配置使用 `.env.example`、`application-local.example.yml` 等示例文件。真实密钥、数据库密码、客户数据和生产配置不得提交到仓库。

## 部署

每个可部署单元独立生成制品和镜像，环境差异通过部署平台或密钥管理系统注入。部署顺序通常为：基础依赖与数据服务、大模型服务、CRM 后端、CRM 前端。

## 注意事项

- SQL 执行账户必须只读，权限、行列控制和脱敏由可信服务端强制实施。
- 日志、测试样例、提示词和截图不得包含真实客户敏感信息。
- `main` 和 `release/*` 应设置为保护分支，禁止开发人员直接推送。
- 生产故障修复使用 `hotfix/*`，合并后打版本标签并保留审计记录。

## 许可

本项目为内部项目，代码、数据和文档的使用范围遵循组织内部管理制度。未经授权不得对外发布或分发。
