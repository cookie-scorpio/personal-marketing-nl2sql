# v1.5 虚构演示数据

本目录提供可重复执行的数据生成程序。它只生成虚构客户、交易、产品持有、营销活动和演示会话，不读取真实客户信息，也不调用大模型。

默认使用固定随机种子 `20260826` 和固定基准日 `2026-08-31`。在参数与代码版本不变时，每次重建得到相同的客户编号、姓名、资产、交易日期和营销数据，便于演示和复测。

## 生成内容

默认规模：

| 数据 | 数量 |
|---|---:|
| 客户 | 10,000 |
| 交易 | 200,000 |
| 产品持有 | 20,000 |
| 营销活动 | 20 |
| 营销触达 | 最多 30,000 |
| 演示会话 | 5 |

业务数据满足以下约束：

- 交易时间不早于客户开户日期，约 2% 交易为失败状态。
- 营销触达时间位于活动开始与结束时间之间，渠道来自活动配置。
- 产品持仓金额由客户资产按比例拆分，不与总资产脱节。
- 三字姓名按“王*明”脱敏，二字姓名按“李*”脱敏。
- 所有账号、客户编号、姓名和手机号占位值均为虚构数据。

## 稳定演示客户

以下客户都属于 `manager01` 的 `M0001` 数据范围：

| 客户编号 | 虚构姓名 | 手机尾号 | 总资产 | 用途 |
|---|---|---:|---:|---|
| `C00000697` | 王小明 | 0697 | 2,253,000 元 | 尾号、同名和王姓筛选 |
| `C00009361` | 陈小满 | 0697 | 683,000 元 | 尾号 0697 多结果 |
| `C00000721` | 王小明 | 0721 | 1,688,000 元 | 完整姓名同名筛选 |
| `C00000241` | 李小红 | 0241 | 917,000 元 | 王先生与李先生对比 |
| `C00000265` | 李小兰 | 0265 | 1,286,000 元 | 李姓多结果 |

因此可以稳定演示：

- “手机号后四位为0697的客户资产是多少”：先出现两位固定尾号客户，再按姓名或编号自动筛选。
- “查询王小明的资产信息”：出现两位同名客户，再按编号或手机号后四位自动筛选。
- “对比王先生和李先生的资产谁更多”：逐位在各自姓氏范围内筛选和确认。

## 首次准备

需要 Python 3.9 或更高版本。先启动 MySQL，再启动一次 v1.5 Spring Boot 后端，让 Flyway 执行 `V1` 至 `V12` 并创建 `manager01` 等演示账号。

```powershell
cd D:\code\boc\0824nl2sql\scripts\mock-data
py -3.9 -m venv .venv
.\.venv\Scripts\pip.exe install -r requirements.txt
$env:MYSQL_PASSWORD = "与后端配置相同的应用数据库密码"
```

如数据库地址、端口、库名或用户不同，可设置 `MYSQL_HOST`、`MYSQL_PORT`、`MYSQL_DATABASE` 和 `MYSQL_USER`，也可以使用同名命令行参数。

## 完整重建

演示环境推荐使用：

```powershell
.\.venv\Scripts\python.exe generate_data.py --reset --reset-runtime --seed-demo-sessions
```

执行顺序为：

1. 清空会话、任务、历史、修复记录和审计等运行时表。
2. 清空本程序管理的六张业务维表和事实表。
3. 使用固定种子和固定基准日重建业务数据。
4. 为 `manager01` 写入 5 个无活动任务的演示会话。

演示会话中的问题不会提前执行。登录后打开会话，点击用户问题旁的“编辑并重新发送”，即可从头展示真实查询流程。

## 常用参数

| 参数 | 说明 |
|---|---|
| `--reset` | 先清空并重建六张演示业务表 |
| `--reset-runtime` | 清空运行时会话、任务、历史和审计 |
| `--seed-demo-sessions` | 为 `manager01` 写入 5 个演示问题会话 |
| `--seed` | 随机种子，默认 `20260826` |
| `--as-of-date` | 数据基准日，默认 `2026-08-31` |
| `--customers` | 客户数，默认 10,000；使用稳定锚点时不要低于 9,361 |
| `--transactions` | 交易数，默认 200,000 |
| `--holdings` | 产品持有数，默认 20,000 |
| `--campaigns` | 营销活动数，默认 20 |
| `--batch-size` | 每批写入行数，默认 1,000 |

只重建业务数据而保留现有会话时：

```powershell
.\.venv\Scripts\python.exe generate_data.py --reset
```

改变 `--seed` 或 `--as-of-date` 会改变大部分统计结果。需要复现实测结果时不要改这两个参数。

## 清理范围

`--reset` 只清理：

- `fct_customer_marketing`
- `fct_transaction`
- `fct_product_holding`
- `dim_marketing_campaign`
- `dim_customer`
- `dim_customer_manager`

`--reset-runtime` 只清理：

- `conversation_message`
- `conversation_session`
- `query_task_event`
- `query_sql_repair`
- `query_task`
- `query_history`
- `audit_event`

它不会删除 `sys_user_account`、`business_term`、Flyway 历史或数据库结构。

## 校验

重建完成后，程序会输出各表生成数量、随机种子、基准日和演示会话数。还可以在 MySQL 中检查：

```sql
SELECT COUNT(*) FROM dim_customer;
SELECT customer_id, customer_name, customer_name_masked, mobile_masked, total_asset_amount
FROM dim_customer
WHERE customer_id IN ('C00000697','C00009361','C00000721','C00000241','C00000265')
ORDER BY customer_id;
SELECT COUNT(*) FROM conversation_session;
```

默认结果应为 10,000 名客户、5 个演示会话，且稳定客户字段与上表一致。

## 注意事项

- `--reset` 和 `--reset-runtime` 会删除数据，只能对明确的演示数据库执行。
- 执行前确认数据库名和连接账号，不要对生产库或共享业务库运行。
- 若只执行 `--seed-demo-sessions` 而不清理旧运行时数据，固定会话编号可能冲突；推荐始终与 `--reset-runtime` 配合。
- `cleanup_runtime_tables.sql` 保留给只想手工清理运行时表的场景，不会自动写入演示会话。
