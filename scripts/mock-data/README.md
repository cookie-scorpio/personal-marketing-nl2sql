# 虚构演示数据生成

本目录提供可重复执行的 Python 程序，为 NL2SQL MVP 生成客户、交易、产品持有和营销活动数据。程序使用固定随机种子和业务分布规则，不调用大模型，也不读取任何真实客户数据。

## 文件说明

- `generate_data.py`：连接 MySQL，按批次生成并写入虚构数据。
- `requirements.txt`：仅包含 MySQL Python 驱动。

## 使用方法

要求 Python 3.9 或更高版本。先启动 MySQL 和v1.2后端一次，让 Flyway 创建表并执行V5，再执行：

```powershell
cd D:\code\boc\0824nl2sql\scripts\mock-data
py -3.9 -m venv .venv
.\.venv\Scripts\pip.exe install -r requirements.txt
$env:MYSQL_PASSWORD = "与 deploy/local/.env 相同的应用密码"
.\.venv\Scripts\python.exe generate_data.py --reset
```

默认生成1万名客户、20万笔交易、2万条产品持有、20个营销活动和最多3万条营销触达记录。可通过 `--customers`、`--transactions`、`--holdings`、`--campaigns` 和 `--seed` 调整。

`--reset` 只清理本程序负责的六张演示业务表，不删除登录账号、查询任务、历史记录或审计数据。生成结果直接进入 MySQL，不在仓库中落地包含客户明细的文件。

## 数据调用链路

```text
generate_data.py → MySQL业务表 → Spring Boot受控SQL → 脱敏结果 → Vue工作台
```

## v1.2 姓名字段

生成器同时写入`customer_name`虚构完整姓名和`customer_name_masked`脱敏姓名，手机号仍仅为虚构脱敏占位值。已有v1.1模拟库由V5生成新的虚构姓名，无需执行重置。原有脱敏姓名不可反向还原，本项目不进行真实姓名恢复。
