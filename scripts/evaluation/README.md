# v1.3 自动评测

本目录测试实际接口行为、生成SQL和结果准确性。标准答案在本地独立计算，不发送给模型。失败用例返回非零退出码，同时保存JSON/CSV；无法识别的结果判为失败，不能把SQL可执行当成业务答案正确。

## 文件与运行要求

| 文件 | 用途 |
|---|---|
| cases-v1.3.json | 3个规则用例、6个模型场景，含三种角色、动态金额分档、图表要求及缺失条件 |
| evaluate.py | 登录测试账号、提交/轮询/确认、比较标准答案、记录SQL与错误及计算通过率 |
| seed_boundaries.py | 向固定隔离库补充19名边界/权限外客户及一客多持有数据，不重置业务库 |
| test_evaluation.py | 验证比较器能拒绝错误人数、错误比例、范围外分组和小数人数 |
| ../verify-v1.3.ps1 | 后端测试、比较器测试、前端测试/构建及可选HTTP测试入口 |

要求Python **3.10或更高版本**（本次使用3.12）、JDK17、Maven3.9.16及已安装依赖的前端。不要使用旧Python/SSL环境访问模型或HTTP。仓库根目录执行：

```powershell
py -3.12 -m venv scripts/evaluation/.venv
scripts/evaluation/.venv/Scripts/python.exe -m pip install -r scripts/evaluation/requirements.txt
scripts/evaluation/.venv/Scripts/python.exe -m unittest discover -s scripts/evaluation
powershell -File scripts/verify-v1.3.ps1 -Python scripts/evaluation/.venv/Scripts/python.exe
```

默认Maven不启用真实MySQL测试，该部分会显示跳过；默认入口不提交外部模型请求。

## 隔离数据库和后端

**禁止用正在给业务人员使用的数据库和后端做验收。**用有创建库权限的本地管理员在MySQL执行以下准备语句，将应用用户名/主机按实际配置调整；脚本不代管管理员密码：

```sql
CREATE DATABASE IF NOT EXISTS pf_nl2sql_v13_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
GRANT ALL ON pf_nl2sql_v13_test.* TO 'nl2sql_app'@'%';
```

测试库需先启动v1.3后端应用V1–V8。另开PowerShell，从仓库根目录运行（已有application-local.yml保留原密码，以下命令行覆盖数据库/端口/provider）：

```powershell
mvn -f crm/backend/pom.xml package -DskipTests
$local = (Resolve-Path crm/backend/src/main/resources/application-local.yml).Path.Replace('\','/')
New-Item -ItemType Directory -Force tmp/v13/logs-18081 | Out-Null
java -jar crm/backend/target/nl2sql-crm-backend-1.3.0.jar `
  "--spring.config.additional-location=file:$local" `
  --server.port=18081 `
  '--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/pf_nl2sql_v13_test?allowPublicKeyRetrieval=true' `
  --spring.data.redis.database=13 --app.model.provider=mock `
  --app.query.sql-log-dir=tmp/v13/logs-18081
```

请确认health为UP且启动日志显示测试库，再填入模拟数据。生成器从`MYSQL_DATABASE`选择库；密码用本地环境变量传入，不提交到Git。**空测试库**可执行，不要添加`--reset`到用户正在使用的库：

```powershell
$env:MYSQL_DATABASE='pf_nl2sql_v13_test'
$env:MYSQL_PASSWORD='填写本地应用密码'
scripts/mock-data/.venv/Scripts/python.exe scripts/mock-data/generate_data.py `
  --customers 10000 --transactions 30000 --holdings 20000 --campaigns 20 --seed 20260826
scripts/evaluation/.venv/Scripts/python.exe scripts/evaluation/seed_boundaries.py
```

生成器虚拟环境需先按[scripts/mock-data说明](../mock-data/README.md)安装。边界脚本只允许`pf_nl2sql_v13_test`，固定编号可重跑补充，存款余额包括0、499999.99、500000、999999.99、1000000、6000000、10000000、10000000.01和12000000；正余额客户分成两条持有记录，防止按记录数误算人数。资产字段故意不等于存款余额，能识别混用AUM的错误。

规则HTTP及真实MySQL集成验证：

```powershell
powershell -File scripts/verify-v1.3.ps1 -Python scripts/evaluation/.venv/Scripts/python.exe -MysqlTests -ApiRules
```

后端集成测试使用local配置的账号连接显式测试库，不调用收费模型。测试会保留模拟任务/会话/审计及少量同名客户夹具；不要将测试历史合并回用户库。

追加的V13HistoryCompatibilityMysqlTest覆盖旧结果恢复不重复、消息时间分页、创建顺序与账号隔离、互斥评价/取消评价及删除后禁止操作。前端`npm test`覆盖旧结果字段与复制内容、账号切换后丢弃迟到200/401。当前含MySQL测试的后端共115项，前端4项，Python比较器5项；具体实际运行证据见[追加验证](../../docs/testing/v1.3追加需求验证记录.md)。

## 真实模型评测与额度

只有在明确获得调用额度后执行。每条用例可能有多次工具续轮/响应重试/SQL修复；评测器会自动确认高风险测试SQL，所以必须是隔离模拟库。

先停止自己的测试后端，在同库的独立18083端口启用deepseek、tools-enabled=true、共享计数文件。**已有计数文件绝不能重置**：

```powershell
$budget = [IO.Path]::GetFullPath('tmp/v13/model-requests.txt')
if (-not (Test-Path -LiteralPath $budget)) { [IO.File]::WriteAllText($budget,'0') }
java -jar crm/backend/target/nl2sql-crm-backend-1.3.0.jar `
  "--spring.config.additional-location=file:$local" --server.port=18083 `
  '--spring.datasource.url=jdbc:mysql://127.0.0.1:3306/pf_nl2sql_v13_test?allowPublicKeyRetrieval=true' `
  --spring.data.redis.database=13 --app.model.provider=deepseek `
  --app.model.deepseek.tools-enabled=true --app.model.request-budget=50 `
  "--app.model.request-budget-file=$budget" --app.query.sql-log-dir=tmp/v13/logs-18083
```

密钥沿用被Git忽略的local文件；若此文件仍为旧token配置，请按主文档调整非敏感项。测试器不能远程核验后端是否配置了同一计数文件，必须核对启动参数；后端计数文件不可用时会停止模型调用。

另开终端执行：

```powershell
scripts/evaluation/.venv/Scripts/python.exe scripts/evaluation/evaluate.py `
  --base-url http://127.0.0.1:18083 --mode model --allow-model `
  --budget-file tmp/v13/model-requests.txt --output tmp/v13/tools-report
```

可用`--ids deposit_original,deposit_variable,gender_pie`选择小样本。对照组在另一个端口启动相同构建、相同配置/数据，唯一差异为tools-enabled=false；两组**顺序运行**并共享计数文件，避免污染分组请求计数。不要同时运行会改变业务数据的集成测试和模型结果评测。测试用例名mode=model表示场景集合，历史缺失条件可能在规则阶段直接澄清，实际消耗以计数器为准。

## 报告与指标

输出默认`tmp/v13/evaluation/`，包含`results.json`、`results.csv`、`summary.json`，目录不提交到Git。主文档的可复核记录在[docs/testing/v1.3验证记录](../../docs/testing/v1.3验证记录.md)。

| 指标 | 判定 |
|---|---|
| behavior_correct | 实际终态/反问类型符合用例 |
| result_correct | 逐档/逐组人数、金额和已返回比例对独立答案；不要求SQL文字相同 |
| chart_correct | PIE实际出现在charts中；TABLE要求没有图形 |
| sql_generated | 从sql-review日志、已保存计划或实际结果找到候选SQL；信息不足为null |
| sql_validation_passed | 后端持久化了已通过最终校验的计划；不等于业务口径正确 |
| sql_executed | 优先看EXECUTED日志；无日志时按SUCCESS判定，并在报告中保留SQL |
| tool_calls | 本任务TOOL_RESULT条数；日志不存在时为null，不能推断未使用工具 |
| model_requests | 本次运行前后持久化计数差，包含失败/重试/续轮 |
| passed | 所有适用检查同时通过 |

存款标准答案先在授权范围内按客户聚合DEPOSIT市值，再由Python分档；0计入首档，上界包含在末档，超过上界排除，零人数档允许SQL不返回。比较器接受元/万元/亿元常见数字标签；未知标签格式判失败待人工复核。人数必须为非负整数，比例容许合理四舍五入误差。chart_correct只验证后端图形描述，浏览器视觉检查另行记录。报告中的百分比仅针对本次小样本，不是整体模型准确率。

评测只允许本地HTTP地址和以_test结尾的库；提交后校验task_id确实写入指定库。这个检查不能撤销误指后端的首条提交，因此启动前仍须核对数据库。默认日志从`tmp/v13/logs-端口/sql-review.log`读取，可用`--sql-log`显式覆盖。脚本没有清除用户数据或重置调用额度的功能。

## 许可

仅用于本项目授权范围内的模拟数据验证，遵守仓库内部使用规定。
