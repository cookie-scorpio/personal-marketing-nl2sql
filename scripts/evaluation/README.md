# v1.5 自动评测

本目录提供规则查询、模型查询和结果比较工具。默认测试不调用真实模型；MySQL 集成和 HTTP 评测必须显式启用，并使用名称以 `_test` 结尾的隔离数据库。

## 文件

- `cases-v1.3.json`：沿用的规则与模型场景数据，文件名仅表示最初建立时间。
- `evaluate.py`：登录、提交问题、等待任务并比较结果。
- `seed_boundaries.py`：在隔离库写入边界数据。
- `test_evaluation.py`：结果比较器单元测试。
- `../verify-v1.5.ps1`：v1.5 后端、前端和比较器统一入口。

## 默认验证

```powershell
cd D:\code\boc\0824nl2sql
python -m pip install -r scripts/evaluation/requirements.txt
powershell -File scripts/verify-v1.5.ps1 -Python python
```

默认执行：

1. Spring Boot 单元测试；
2. Python 结果比较器测试；
3. 前端回归测试；
4. 前端生产构建。

默认不连接 MySQL、不启动服务，也不调用模型。

## MySQL 集成测试

先准备独立测试库，例如 `pf_nl2sql_v15_test`，再执行：

```powershell
powershell -File scripts/verify-v1.5.ps1 -MysqlTests -Database pf_nl2sql_v15_test
```

脚本会拒绝不以 `_test` 结尾的库名。集成测试会写入任务、会话和审计数据，不得指向演示库。

## HTTP 规则评测

先在隔离测试库上启动后端，再执行：

```powershell
powershell -File scripts/verify-v1.5.ps1 -ApiRules -BaseUrl http://127.0.0.1:18081 -Database pf_nl2sql_v15_test
```

## 真实模型评测

真实模型评测必须显式提供同一份调用次数预算文件：

```powershell
powershell -File scripts/verify-v1.5.ps1 -RealModel -BaseUrl http://127.0.0.1:18081 -Database pf_nl2sql_v15_test -BudgetFile tmp/llm-budget/v15.txt
```

只有明确需要复测模型生成质量时才启用。测试输出位于 `tmp/v15/evaluation/`，不提交到 Git。

## 注意事项

- 不要把演示库、开发共享库或生产库传给评测脚本。
- HTTP 评测前确认后端数据源与命令行 `Database` 指向同一测试库。
- 模型评测会产生外部调用费用，并受预算文件限制。
- 当前产品功能、数据库和接口验收口径见 [v1.5 实施说明](../../docs/v1.5实施说明与接口数据字典.md)。
