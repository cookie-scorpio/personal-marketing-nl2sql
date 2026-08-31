# 个金营销 NL2SQL 平台

本项目用于演示个人金融营销场景中的自然语言查询。业务人员可以直接询问模拟客户、交易、产品持有和营销活动数据，系统会在账号权限范围内生成并校验只读 SQL，再以指标、图表、表格和简要分析展示结果。

当前产品版本统一为 **v1.5**。本次版本汇总了 v1.4 之后的全部改动，包括连续对话、客户定位与两人对比、会话删除、结果导出、SQL 修复记录、图表展示、脱敏和稳定演示数据。完整功能、数据库设计、接口和升级说明见 [v1.5 实施说明与接口数据字典](docs/v1.5实施说明与接口数据字典.md)。

## 主要功能

- Vue 3 对话工作台，支持会话历史、连续追问、取消、编辑重发、回复评价和 CSV 导出。
- Spring Boot 后端统一处理登录、客户范围、自然语言识别、SQL 校验、执行、审计和结果整理。
- 明确场景优先使用规则，自由问题可由 DeepSeek 生成查询计划；每次生成或修复后的 SQL 都重新校验。
- 客户姓名、编号和手机号后四位定位；原问句条件锁定，多结果时在限定范围内自动筛选。
- 两位客户逐位定位和资产比较；固定条件唯一时自动继续，零匹配时直接结束。
- MySQL 保存业务数据、账号、任务、会话和审计；Redis 用于短期幂等索引，暂时不可用时仍由 MySQL 保证一致性。

## 目录

- `crm/frontend/`：Vue 3 + TypeScript 前端。
- `crm/backend/`：Spring Boot 后端和 Flyway 数据库迁移。
- `scripts/mock-data/`：稳定的虚构业务数据和演示会话生成工具。
- `contracts/openapi/`：前后端接口契约。
- `deploy/local/`：本地 MySQL 和 Redis 容器配置。
- `docs/`：v1.0 至 v1.5 六份实施说明。

## 安装与配置

需要 JDK 17、Maven 3.9、Node.js 20、Python 3.9 和 Docker Desktop。

1. 按 [本地基础服务说明](deploy/local/README.md)启动 MySQL 8.4 和 Redis 7。
2. 按 [后端说明](crm/backend/README.md)准备 `application-local.yml`，至少配置数据库密码和 `JWT_SECRET`。
3. 首次启动一次后端，让 Flyway 建表并创建演示账号。
4. 按 [模拟数据说明](scripts/mock-data/README.md)重建稳定业务数据和演示会话。
5. 按 [前端说明](crm/frontend/README.md)安装依赖并启动页面。

自由问题需要设置 `MODEL_PROVIDER=deepseek`、`DEEPSEEK_API_KEY` 和相应模型名称；只演示规则查询时可以使用默认的 `mock` 模式。

## 运行

后端：

```powershell
cd crm/backend
$env:SPRING_PROFILES_ACTIVE = "local"
mvn spring-boot:run
```

前端：

```powershell
cd crm/frontend
npm install
npm run dev
```

浏览器访问 `http://127.0.0.1:5173`。演示账号为 `manager01`、`leader01` 和 `director01`，初始密码均为 `Demo@123`。

## 模拟数据

以下命令会重建固定基准日和固定随机种子的业务数据，清空测试会话，并为 `manager01` 留下 5 个可编辑重发的演示问题：

```powershell
cd scripts/mock-data
$env:MYSQL_PASSWORD = "本地应用数据库密码"
.\.venv\Scripts\python.exe generate_data.py --reset --reset-runtime --seed-demo-sessions
```

该命令只适用于演示环境。具体数据规模、稳定客户编号、清理范围和校验方法见 [模拟数据说明](scripts/mock-data/README.md)。

## 测试

```powershell
mvn -f crm/backend/pom.xml test
cd crm/frontend
npm test
npm run build
```

后端数据库集成测试只能在名称以 `_test` 结尾的隔离库执行，不要对演示库或其他共享数据库开启集成测试。

## 部署

先准备 MySQL 和 Redis，再部署后端 JAR，最后把前端 `npm run build` 生成的 `dist/` 交给静态服务器。静态服务器需要将 `/api` 和健康检查转发到后端；SSE 代理应关闭缓冲，并把读取超时设置为 60 秒以上。密码、JWT 密钥和模型密钥必须通过外部配置注入。

## 注意事项

- 项目只使用虚构数据，不得导入真实客户信息。
- SQL 只允许读取白名单表，并强制加入当前账号的数据范围；生产环境仍需独立只读账号、列级权限和统一认证。
- 不要提交 `.env`、`application-local.yml`、日志、模型密钥或数据库备份。
- Flyway 的 `V1` 至 `V12` 是数据库迁移编号，不等同于产品版本，升级时不得重命名已执行的迁移文件。

## 许可

本项目为内部项目，代码、数据和文档的使用范围遵循组织内部管理制度。未经授权不得对外发布或分发。
