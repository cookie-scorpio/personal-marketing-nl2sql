"""Generate reproducible, fully synthetic marketing data for the NL2SQL MVP.

The script deliberately uses invalid/masked identifiers instead of plausible real PII.
It writes data in batches so the default 200k transactions fit on an ordinary laptop.

v1.5 数据质量约定（与 docs/v1.5实施说明 一致）：
- 交易时间必须晚于客户开户日期；
- 营销触达时间落在对应活动窗口内，触达渠道来自活动申报渠道；
- 持仓市值由客户总资产拆分生成，不再与资产无关；
- 交易金额呈对数正态分布（利息类小额），约2%为 FAILED 状态，使 status_code 口径可演示；
- 生成前必须 --reset 清理旧演示数据，避免历史客户（如集成测试夹具）残留。
"""

from __future__ import annotations

import argparse
import math
import os
import random
import uuid
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Iterable, Sequence


DEFAULT_SEED = 20260826
DEFAULT_AS_OF_DATE = date(2026, 8, 31)
SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜"
GIVEN_NAMES = "安柏博辰承初楚淳达丹冬恩帆芳飞枫歌涵航和衡恒宏泓华嘉佳江锦靖景静君俊凯康岚朗乐礼林霖凌明铭沐宁诺佩平启清秋然仁荣瑞若山杉尚诗书思松苏棠天庭彤桐宛维文闻溪希夏贤向晓心欣新星修轩雪雅言彦阳尧一依宜亦逸奕音盈颖瑜雨语宇羽远悦云泽知致中舟竹卓梓"
OCCUPATIONS = ["FINANCE", "EDUCATION", "HEALTHCARE", "TECH", "MANUFACTURING", "RETAIL", "OTHER"]
RISK_LEVELS = ["R1", "R2", "R3", "R4", "R5"]
TRANSACTION_TYPES = ["CONSUME", "TRANSFER", "DEPOSIT", "WITHDRAW", "INTEREST"]
PRODUCT_CATEGORIES = ["WEALTH", "FUND", "DEPOSIT"]
CONTACT_CHANNELS = ["APP", "SMS", "PHONE"]


@dataclass(frozen=True)
class Settings:
    customers: int
    transactions: int
    holdings: int
    campaigns: int
    seed: int
    batch_size: int
    as_of_date: date


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="生成个金营销 NL2SQL 全量虚构演示数据")
    parser.add_argument("--host", default=os.getenv("MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")))
    parser.add_argument("--database", default=os.getenv("MYSQL_DATABASE", "pf_nl2sql"))
    parser.add_argument("--user", default=os.getenv("MYSQL_USER", "nl2sql_app"))
    parser.add_argument("--password", default=os.getenv("MYSQL_PASSWORD"), required=os.getenv("MYSQL_PASSWORD") is None)
    parser.add_argument("--customers", type=int, default=10_000)
    parser.add_argument("--transactions", type=int, default=200_000)
    parser.add_argument("--holdings", type=int, default=20_000)
    parser.add_argument("--campaigns", type=int, default=20)
    parser.add_argument("--seed", type=int, default=DEFAULT_SEED)
    parser.add_argument("--batch-size", type=int, default=1_000)
    parser.add_argument(
        "--as-of-date",
        type=date.fromisoformat,
        default=date.fromisoformat(os.getenv("DEMO_AS_OF_DATE", DEFAULT_AS_OF_DATE.isoformat())),
        help=f"数据基准日，默认 {DEFAULT_AS_OF_DATE.isoformat()}；固定该值可得到一致结果",
    )
    parser.add_argument("--reset", action="store_true", help="先清空本程序管理的演示业务表")
    parser.add_argument("--reset-runtime", action="store_true", help="清空会话、任务、历史和审计等运行时表（仅演示环境）")
    parser.add_argument("--seed-demo-sessions", action="store_true", help="为 manager01 写入可编辑重发的演示问题会话")
    return parser.parse_args()


def chunks(rows: Sequence[tuple], size: int) -> Iterable[Sequence[tuple]]:
    for start in range(0, len(rows), size):
        yield rows[start : start + size]


def insert_batches(cursor, sql: str, rows: Sequence[tuple], batch_size: int) -> None:
    for batch in chunks(rows, batch_size):
        cursor.executemany(sql, batch)


def money_by_level(rng: random.Random, level: str) -> Decimal:
    ranges = {
        "NORMAL": (10_000, 500_000),
        "GOLD": (500_000, 1_200_000),
        "PLATINUM": (1_000_000, 12_000_000),
    }
    low, high = ranges[level]
    return Decimal(rng.randint(low, high)).quantize(Decimal("0.01"))


def mask_name(value: str) -> str:
    """与后端一致：二字姓名保留姓氏，三字及以上保留首末字。"""
    if len(value) <= 2:
        return value[:1] + "*"
    return value[:1] + "*" * (len(value) - 2) + value[-1:]


# 经理 manager01 范围内的稳定演示锚点。编号、姓名、尾号和资产跨重建保持不变。
DEMO_CUSTOMERS = {
    241: {"name": "李小红", "mobile": "900****0241", "asset": Decimal("917000.00")},
    265: {"name": "李小兰", "mobile": "900****0265", "asset": Decimal("1286000.00")},
    697: {"name": "王小明", "mobile": "900****0697", "asset": Decimal("2253000.00")},
    721: {"name": "王小明", "mobile": "900****0721", "asset": Decimal("1688000.00")},
    9361: {"name": "陈小满", "mobile": "900****0697", "asset": Decimal("683000.00")},
}


def build_managers() -> list[tuple]:
    rows = []
    for index in range(1, 25):
        branch = f"B{((index - 1) // 3) + 1:03d}"
        region = "EAST" if int(branch[1:]) <= 4 else "SOUTH"
        rows.append((f"M{index:04d}", f"演示经理{index:02d}", branch, region, "ACTIVE"))
    return rows


def build_customers(settings: Settings, rng: random.Random) -> list[tuple]:
    today = settings.as_of_date
    managers = build_managers()
    rows = []
    capacity = len(SURNAMES) * len(GIVEN_NAMES) ** 2
    if settings.customers > capacity:
        raise ValueError(f"唯一虚构姓名支持最多{capacity}名客户")
    name_codes = random.Random(settings.seed).sample(range(capacity), settings.customers)
    for index in range(1, settings.customers + 1):
        manager_id, _, branch_id, region_code, _ = managers[(index - 1) % len(managers)]
        level = rng.choices(["NORMAL", "GOLD", "PLATINUM"], weights=[68, 22, 10], k=1)[0]
        age = rng.randint(20, 72)
        age_band = "A18_25" if age <= 25 else "A26_35" if age <= 35 else "A36_45" if age <= 45 else "A46_60" if age <= 60 else "A60_PLUS"
        code = name_codes[index - 1]
        full_name = SURNAMES[code // len(GIVEN_NAMES) ** 2] + GIVEN_NAMES[code // len(GIVEN_NAMES) % len(GIVEN_NAMES)] + GIVEN_NAMES[code % len(GIVEN_NAMES)]
        anchor = DEMO_CUSTOMERS.get(index)
        if anchor:
            full_name = anchor["name"]
        synthetic_name = mask_name(full_name)
        # 900 开头不构造真实中国手机号，末四位只用于演示脱敏格式。
        mobile_masked = anchor["mobile"] if anchor else f"900****{index % 10_000:04d}"
        asset = anchor["asset"] if anchor else money_by_level(rng, level)
        change_rate = Decimal(str(round(rng.uniform(-0.42, 0.35), 4)))
        rows.append((
            f"C{index:08d}", synthetic_name, rng.choice(["M", "F", "U"]), age, age_band,
            mobile_masked, level, level == "PLATINUM", rng.choice(RISK_LEVELS),
            rng.choice(OCCUPATIONS), region_code, branch_id, manager_id, asset, change_rate,
            today - timedelta(days=rng.randint(30, 5000)), "ACTIVE", today, full_name,
        ))
    return rows


def build_campaigns(settings: Settings, rng: random.Random) -> list[tuple]:
    today = datetime.combine(settings.as_of_date, datetime.min.time())
    names = ["财富季理财提升", "代发客户唤醒", "基金定投成长", "存量客户关怀", "高净值客户专享"]
    rows = []
    for index in range(1, settings.campaigns + 1):
        start = today - timedelta(days=rng.randint(5, 260))
        end = start + timedelta(days=rng.randint(30, 120))
        category = PRODUCT_CATEGORIES[index % len(PRODUCT_CATEGORIES)]
        rows.append((
            f"CMP{index:04d}", f"{names[(index - 1) % len(names)]}-{index:02d}",
            rng.choice(["ACQUISITION", "UPSELL", "RETENTION", "CARE"]),
            "RUNNING" if end >= today else "FINISHED", f"P{index:04d}",
            rng.choice(["AUM_50W_PLUS", "PLATINUM", "ACTIVE"]), rng.choice(["APP", "SMS", "PHONE", "MULTI"]),
            "EAST" if index <= settings.campaigns // 2 else "SOUTH", None, start, end,
            Decimal(rng.randint(50_000, 800_000)), rng.randint(500, 5_000),
        ))
    return rows


def build_holdings(settings: Settings, rng: random.Random,
                   customer_assets: dict[int, Decimal]) -> list[tuple]:
    """持仓由客户总资产拆分生成：市值合计约束在资产以内，类别构成合理。"""
    today = settings.as_of_date
    pairs: set[tuple[int, int]] = set()
    rows = []
    # 按客户等级决定持有概率，直到达到目标行数；保证不重复（客户，产品）。
    level_weight = {"NORMAL": 0.55, "GOLD": 0.8, "PLATINUM": 0.95}
    visits = 0
    while len(rows) < settings.holdings and visits < settings.customers * 3:
        visits += 1
        idx = rng.randint(1, settings.customers)
        level = ("PLATINUM" if idx % 10 == 0 else "GOLD" if idx % 10 in (3, 7) else "NORMAL")
        if rng.random() > level_weight[level]:
            continue
        asset = customer_assets.get(idx) or Decimal(10_000)
        product_count = rng.randint(1, 4)
        if len(rows) + product_count > settings.holdings:
            product_count = max(1, settings.holdings - len(rows))
        # 存款占资产30%-60%，其余分配给理财/基金；整体乘以折算系数保证合计不超过资产。
        deposit_share = rng.uniform(0.30, 0.60)
        wealth_share = (1 - deposit_share) * rng.uniform(0.4, 0.7)
        fund_share = 1 - deposit_share - wealth_share
        mix = {"DEPOSIT": deposit_share, "WEALTH": wealth_share, "FUND": fund_share}
        chosen = rng.sample(list(mix.keys()), min(product_count, 3))
        total = sum(mix[c] for c in chosen)
        haircut = rng.uniform(0.35, 0.95)
        for category in chosen:
            product_index = rng.randint(1, 120)
            if (idx, product_index) in pairs:
                continue
            pairs.add((idx, product_index))
            amount = asset * Decimal(str(round(mix[category] / total * haircut / 2, 6)))
            if amount < Decimal(1_000):
                amount = Decimal(rng.randint(1_000, 8_000))
            amount = amount.quantize(Decimal("0.01"))
            market = (amount * Decimal(str(round(rng.uniform(0.92, 1.18), 4)))).quantize(Decimal("0.01"))
            rows.append((
                f"C{idx:08d}", f"P{product_index:04d}", f"演示{category}产品{product_index:03d}",
                category, amount, market, market - amount,
                today + timedelta(days=rng.randint(10, 720)) if category != "DEPOSIT" else None,
                rng.choice(RISK_LEVELS), today,
            ))
            if len(rows) >= settings.holdings:
                break
    return rows


def build_transactions(settings: Settings, rng: random.Random,
                       open_dates: dict[int, date]) -> list[tuple]:
    """交易时间不早于开户日；金额对数正态（利息小额）；约2% FAILED。"""
    now = datetime.combine(settings.as_of_date, datetime.max.time()).replace(microsecond=0)
    rows = []
    for index in range(1, settings.transactions + 1):
        customer_index = rng.randint(1, settings.customers)
        manager_index = (customer_index - 1) % 24 + 1
        branch_id = f"B{((manager_index - 1) // 3) + 1:03d}"
        open_day = open_dates.get(customer_index, now.date() - timedelta(days=365))
        earliest = max(open_day, now.date() - timedelta(days=364))
        if earliest > now.date():
            earliest = now.date()
        span_days = (now.date() - earliest).days
        happened = datetime.combine(
            earliest + timedelta(days=rng.randint(0, max(span_days, 0))),
            datetime.min.time(),
        ) + timedelta(seconds=rng.randint(0, 86_399))
        if happened > now:
            happened = now - timedelta(minutes=rng.randint(1, 60))
        tx_type = rng.choice(TRANSACTION_TYPES)
        if tx_type == "INTEREST":
            amount = Decimal(rng.randint(5, 3_000)).quantize(Decimal("0.01"))
        else:
            amount = Decimal(min(500_000, max(10, int(math.exp(rng.gauss(8.2, 1.35)))))).quantize(Decimal("0.01"))
        status = "FAILED" if rng.random() < 0.02 else "SUCCESS"
        rows.append((
            f"TXN{index:010d}", f"C{customer_index:08d}", f"P{rng.randint(1, 120):04d}",
            happened, happened.date(), tx_type, "C" if tx_type in {"DEPOSIT", "INTEREST"} else "D",
            "CNY", amount, branch_id, status,
        ))
    return rows


def build_marketing_relations(settings: Settings, rng: random.Random,
                              campaign_windows: dict[int, tuple[datetime, datetime, str]]) -> list[tuple]:
    """触达时间必须落在活动窗口内，渠道取自活动申报渠道（MULTI 随机展开）。"""
    now = datetime.combine(settings.as_of_date, datetime.max.time()).replace(microsecond=0)
    target = min(settings.customers * 3, 30_000)
    pairs: set[tuple[int, int]] = set()
    rows = []
    while len(rows) < target:
        campaign_index = rng.randint(1, settings.campaigns)
        customer_index = rng.randint(1, settings.customers)
        if (campaign_index, customer_index) in pairs:
            continue
        pairs.add((campaign_index, customer_index))
        start, end, declared = campaign_windows[campaign_index]
        # 活动可能仍在进行：触达时间取 [start, min(end, now)] 内的均匀分布。
        latest = min(end, now)
        if latest <= start:
            latest = start + timedelta(hours=1)
        window_seconds = int((latest - start).total_seconds())
        contact = start + timedelta(seconds=rng.randint(0, window_seconds))
        channels = CONTACT_CHANNELS if declared == "MULTI" else [declared]
        response = rng.random() < rng.uniform(0.25, 0.5)
        conversion = response and rng.random() < rng.uniform(0.15, 0.35)
        rows.append((
            f"CMP{campaign_index:04d}", f"C{customer_index:08d}",
            contact.replace(microsecond=0), rng.choice(channels), response, conversion,
            Decimal(rng.randint(2_000, 200_000)) if conversion else Decimal("0"),
        ))
    return rows


def reset_demo_tables(cursor) -> None:
    # 仅清理本脚本管理的明确表名，不操作账号、任务、历史和审计表。
    for table in ["fct_customer_marketing", "fct_transaction", "fct_product_holding", "dim_marketing_campaign", "dim_customer", "dim_customer_manager"]:
        cursor.execute(f"DELETE FROM {table}")


def reset_runtime_tables(cursor) -> None:
    """只清理明确列出的运行时表，不动账号、业务数据和 Flyway 历史。"""
    cursor.execute("SET FOREIGN_KEY_CHECKS = 0")
    for table in [
        "conversation_message", "conversation_session", "query_task_event", "query_sql_repair",
        "query_task", "query_history", "audit_event",
    ]:
        cursor.execute(f"TRUNCATE TABLE {table}")
    cursor.execute("SET FOREIGN_KEY_CHECKS = 1")


DEMO_SESSIONS = [
    ("客户尾号定位", "手机号后四位为0697的客户资产是多少"),
    ("同名客户定位", "查询王小明的资产信息"),
    ("两位客户对比", "对比王先生和李先生的资产谁更多"),
    ("交易趋势分析", "用折线图展示近90天各渠道交易金额变化"),
    ("客户等级概览", "按客户等级统计客户数和平均资产，用柱状图展示"),
]


def seed_demo_sessions(cursor, as_of_date: date) -> None:
    """写入无活动任务的演示问题卡片；用户可在界面点击“编辑并重新发送”启动真实流程。"""
    cursor.execute("SELECT id FROM sys_user_account WHERE username=%s AND enabled=TRUE", ("manager01",))
    row = cursor.fetchone()
    if not row:
        raise RuntimeError("未找到 manager01。请先启动一次 Spring 后端，让演示账号完成初始化。")
    user_id = row[0]
    base_time = datetime.combine(as_of_date, datetime.min.time()).replace(hour=9)
    for index, (title, question) in enumerate(DEMO_SESSIONS, start=1):
        session_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"nl2sql-v1.5-session-{index}"))
        task_id = str(uuid.uuid5(uuid.NAMESPACE_URL, f"nl2sql-v1.5-message-{index}"))
        created = base_time + timedelta(minutes=index)
        cursor.execute(
            "INSERT INTO conversation_session(session_id,user_id,title,active_task_id,context_json,state_version,created_at,updated_at,deleted_at) "
            "VALUES(%s,%s,%s,NULL,NULL,0,%s,%s,NULL)",
            (session_id, user_id, title, created, created + timedelta(seconds=1)),
        )
        cursor.execute(
            "INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,payload_json,created_at,updated_at) "
            "VALUES(%s,%s,'USER','demo-question',%s,NULL,%s,%s)",
            (session_id, task_id, question, created, created),
        )
        cursor.execute(
            "INSERT INTO conversation_message(session_id,task_id,role_code,message_key,content,payload_json,created_at,updated_at) "
            "VALUES(%s,%s,'ASSISTANT','demo-guide',%s,NULL,%s,%s)",
            (session_id, task_id, "演示问题已准备好。点击上方问题的编辑按钮并重新发送，即可从头演示完整查询流程。",
             created + timedelta(seconds=1), created + timedelta(seconds=1)),
        )


def main() -> None:
    args = parse_args()
    # 延迟加载数据库驱动，使 `--help` 在尚未创建虚拟环境时也能正常使用。
    try:
        import mysql.connector
    except ModuleNotFoundError as exc:
        raise SystemExit(
            "缺少 mysql-connector-python。请先执行：python -m pip install -r requirements.txt"
        ) from exc
    settings = Settings(args.customers, args.transactions, args.holdings, args.campaigns, args.seed, args.batch_size, args.as_of_date)
    rng = random.Random(settings.seed)
    connection = mysql.connector.connect(
        host=args.host, port=args.port, database=args.database, user=args.user, password=args.password,
        charset="utf8mb4", autocommit=False,
    )
    try:
        cursor = connection.cursor()
        if args.reset_runtime:
            reset_runtime_tables(cursor)
            connection.commit()
        if args.reset:
            reset_demo_tables(cursor)
            connection.commit()

        managers = build_managers()
        customers = build_customers(settings, rng)
        campaigns = build_campaigns(settings, rng)
        # 后续事实数据依赖维表：开户日期、资产、活动窗口。
        customer_assets = {i: row[13] for i, row in enumerate(customers, start=1)}
        open_dates = {i: row[15] for i, row in enumerate(customers, start=1)}
        campaign_windows = {
            i: (row[9], row[10], row[6]) for i, row in enumerate(campaigns, start=1)
        }
        holdings = build_holdings(settings, rng, customer_assets)
        marketing = build_marketing_relations(settings, rng, campaign_windows)
        transactions = build_transactions(settings, rng, open_dates)

        cursor.executemany("INSERT INTO dim_customer_manager VALUES (%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE manager_name=VALUES(manager_name),branch_id=VALUES(branch_id),region_code=VALUES(region_code)", managers)
        insert_batches(cursor, """INSERT INTO dim_customer(customer_id,customer_name_masked,gender_code,age,age_band_code,mobile_masked,customer_level_code,vip_flag,risk_level_code,occupation_code,region_code,branch_id,manager_id,total_asset_amount,asset_change_3m_rate,open_date,status_code,snapshot_date,customer_name) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", customers, settings.batch_size)
        insert_batches(cursor, """INSERT INTO dim_marketing_campaign(campaign_id,campaign_name,campaign_type_code,campaign_status_code,product_id,target_customer_segment_code,channel_code,owner_org_id,owner_manager_id,start_time,end_time,budget_amount,target_count) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", campaigns, settings.batch_size)
        insert_batches(cursor, """INSERT INTO fct_product_holding(customer_id,product_id,product_name,product_category_code,holding_amount,market_value_amount,profit_amount,maturity_date,risk_level_code,snapshot_date) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", holdings, settings.batch_size)
        insert_batches(cursor, """INSERT INTO fct_customer_marketing(campaign_id,customer_id,contact_time,contact_channel_code,response_flag,conversion_flag,conversion_amount) VALUES (%s,%s,%s,%s,%s,%s,%s)""", marketing, settings.batch_size)
        insert_batches(cursor, """INSERT INTO fct_transaction(transaction_id,customer_id,product_id,transaction_time,transaction_date,transaction_type_code,debit_credit_flag,currency_code,amount_cny,branch_id,status_code) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", transactions, settings.batch_size)
        if args.seed_demo_sessions:
            seed_demo_sessions(cursor, settings.as_of_date)
        connection.commit()
        print(f"Generated customers={len(customers)}, transactions={len(transactions)}, holdings={len(holdings)}, campaigns={len(campaigns)}, marketing_relations={len(marketing)}, demo_sessions={len(DEMO_SESSIONS) if args.seed_demo_sessions else 0}, seed={settings.seed}, as_of_date={settings.as_of_date}")
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
