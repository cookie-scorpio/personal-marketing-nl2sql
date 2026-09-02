# 个金营销 NL2SQL 平台

本项目用于演示个人金融营销场景中的自然语言查询。业务人员可以直接询问模拟客户、交易、产品持有和营销活动数据，系统会在账号权限范围内生成并校验只读 SQL，再以指标、图表、表格和简要分析展示结果。

当前产品版本统一为 **v1.6**。本版在原有问数能力上增加五位工号注册、待审批账号、多身份授权、身份切换、质量审计入口和权限管理。质量审计员可以使用智能问数，其会话与客户经理身份严格分开；切换身份只重建当前工作区，不触发浏览器整页跳转。完整功能、数据库设计、接口和升级说明见 [v1.6 实施说明与接口数据字典](docs/v1.6实施说明与接口数据字典.md)。

## 主要功能

- Vue 3 对话工作台，支持会话历史、连续追问、取消、编辑重发、回复评价和 CSV 导出。
- 同一账号可获授客户经理、质量审计员和权限管理员身份；身份切换由后端复核并重新签发 JWT。
- 客户经理按经理、机构或区域查询；质量审计员可查询全部在册客户，两类会话按当前身份独立保存和显示。
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
- `docs/`：v1.0 至 v1.6 七份实施说明。

## 安装与配置

需要 JDK 17、Maven 3.9.16、Node.js 20、Python 3.9 和 Docker Desktop。

拉取仓库后，推荐用 VS Code 的“任务: 运行任务”依次执行“启动后端（本地开发）”和“启动前端（本地开发）”。后端任务会自动复制开发用 `.env`、启动 MySQL 8.4 和 Redis 7.x、生成仅在本次进程有效的 RSA 密钥与 JWT 密钥；前端任务会在首次启动时执行 `npm ci`。浏览器访问 `http://localhost:5173`，因为浏览器密码加密仅支持当前的 `localhost` 开发模式。

1. 确认 Docker Desktop 已启动，再按上述 VS Code 任务启动后端和前端。
2. 首次启动后端时，Flyway 会建表并创建演示账号。
3. 如需完整的业务演示数据，再按 [模拟数据说明](scripts/mock-data/README.md)写入稳定数据和演示会话。

自由问题需要设置 `MODEL_PROVIDER=deepseek`、`DEEPSEEK_API_KEY` 和相应模型名称；只演示规则查询时可以使用默认的 `mock` 模式。

## 运行

后端：

```powershell
.\scripts\start-backend-local.ps1
```

前端：

```powershell
.\scripts\start-frontend-local.ps1
```

浏览器访问 `http://127.0.0.1:5173`。`manager01`、`leader01` 是客户经理类账号，`director01` 可在机构负责人和质量审计员之间切换，`quality01` 是质量审计员，`admin01` 是权限管理员；初始密码均为 `Demo@123`。

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
- Flyway 的 `V1` 至 `V17` 是数据库迁移编号，不等同于产品版本，升级时不得重命名或修改已执行的迁移文件。

## 许可

本项目为内部项目，代码、数据和文档的使用范围遵循组织内部管理制度。未经授权不得对外发布或分发。
