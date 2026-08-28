"""Generate reproducible, fully synthetic marketing data for the NL2SQL MVP.

The script deliberately uses invalid/masked identifiers instead of plausible real PII.
It writes data in batches so the default 200k transactions fit on an ordinary laptop.
"""

from __future__ import annotations

import argparse
import os
import random
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from decimal import Decimal
from typing import Iterable, Sequence


DEFAULT_SEED = 20260826
SURNAMES = "赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦许何吕施张孔曹严华金魏陶姜" 
GIVEN_NAMES = "安柏博辰承初楚淳达丹冬恩帆芳飞枫歌涵航和衡恒宏泓华嘉佳江锦靖景静君俊凯康岚朗乐礼林霖凌明铭沐宁诺佩平启清秋然仁荣瑞若山杉尚诗书思松苏棠天庭彤桐宛维文闻溪希夏贤向晓心欣新星修轩雪雅言彦阳尧一依宜亦逸奕音盈颖瑜雨语宇羽远悦云泽知致中舟竹卓梓"
OCCUPATIONS = ["FINANCE", "EDUCATION", "HEALTHCARE", "TECH", "MANUFACTURING", "RETAIL", "OTHER"]
RISK_LEVELS = ["R1", "R2", "R3", "R4", "R5"]
TRANSACTION_TYPES = ["CONSUME", "TRANSFER", "DEPOSIT", "WITHDRAW", "INTEREST"]
PRODUCT_CATEGORIES = ["WEALTH", "FUND", "DEPOSIT"]


@dataclass(frozen=True)
class Settings:
    customers: int
    transactions: int
    holdings: int
    campaigns: int
    seed: int
    batch_size: int


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
    parser.add_argument("--reset", action="store_true", help="先清空本程序管理的演示业务表")
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


def build_managers() -> list[tuple]:
    rows = []
    for index in range(1, 25):
        branch = f"B{((index - 1) // 3) + 1:03d}"
        region = "EAST" if int(branch[1:]) <= 4 else "SOUTH"
        rows.append((f"M{index:04d}", f"演示经理{index:02d}", branch, region, "ACTIVE"))
    return rows


def build_customers(settings: Settings, rng: random.Random) -> list[tuple]:
    today = date.today()
    managers = build_managers()
    rows = []
    capacity=len(SURNAMES)*len(GIVEN_NAMES)**2
    if settings.customers>capacity:
        raise ValueError(f"唯一虚构姓名支持最多{capacity}名客户")
    name_codes=random.Random(settings.seed).sample(range(capacity),settings.customers)
    for index in range(1, settings.customers + 1):
        manager_id, _, branch_id, region_code, _ = managers[(index - 1) % len(managers)]
        level = rng.choices(["NORMAL", "GOLD", "PLATINUM"], weights=[68, 22, 10], k=1)[0]
        age = rng.randint(20, 72)
        age_band = "A18_25" if age <= 25 else "A26_35" if age <= 35 else "A36_45" if age <= 45 else "A46_60" if age <= 60 else "A60_PLUS"
        code=name_codes[index-1]
        full_name=SURNAMES[code // len(GIVEN_NAMES)**2]+GIVEN_NAMES[code // len(GIVEN_NAMES) % len(GIVEN_NAMES)]+GIVEN_NAMES[code % len(GIVEN_NAMES)]
        synthetic_name = full_name[0] + '*' * max(1, len(full_name) - 1)
        # 900 开头不构造真实中国手机号，末四位只用于演示脱敏格式。
        mobile_masked = f"900****{index % 10_000:04d}"
        asset = money_by_level(rng, level)
        change_rate = Decimal(str(round(rng.uniform(-0.42, 0.35), 4)))
        rows.append((
            f"C{index:08d}", synthetic_name, rng.choice(["M", "F", "U"]), age, age_band,
            mobile_masked, level, level == "PLATINUM", rng.choice(RISK_LEVELS),
            rng.choice(OCCUPATIONS), region_code, branch_id, manager_id, asset, change_rate,
            today - timedelta(days=rng.randint(30, 5000)), "ACTIVE", today, full_name,
        ))
    return rows


def build_campaigns(settings: Settings, rng: random.Random) -> list[tuple]:
    today = datetime.now().replace(hour=0, minute=0, second=0, microsecond=0)
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


def build_holdings(settings: Settings, rng: random.Random) -> list[tuple]:
    today = date.today()
    pairs: set[tuple[int, int]] = set()
    rows = []
    while len(rows) < settings.holdings:
        customer_index = rng.randint(1, settings.customers)
        product_index = rng.randint(1, 120)
        if (customer_index, product_index) in pairs:
            continue
        pairs.add((customer_index, product_index))
        category = PRODUCT_CATEGORIES[product_index % len(PRODUCT_CATEGORIES)]
        amount = Decimal(rng.randint(5_000, 900_000)).quantize(Decimal("0.01"))
        market = (amount * Decimal(str(round(rng.uniform(0.92, 1.18), 4)))).quantize(Decimal("0.01"))
        rows.append((
            f"C{customer_index:08d}", f"P{product_index:04d}", f"演示{category}产品{product_index:03d}",
            category, amount, market, market - amount,
            today + timedelta(days=rng.randint(10, 720)) if category != "DEPOSIT" else None,
            rng.choice(RISK_LEVELS), today,
        ))
    return rows


def build_transactions(settings: Settings, rng: random.Random) -> list[tuple]:
    now = datetime.now().replace(microsecond=0)
    rows = []
    for index in range(1, settings.transactions + 1):
        customer_index = rng.randint(1, settings.customers)
        manager_index = (customer_index - 1) % 24 + 1
        branch_id = f"B{((manager_index - 1) // 3) + 1:03d}"
        happened = now - timedelta(days=rng.randint(0, 364), seconds=rng.randint(0, 86_399))
        tx_type = rng.choice(TRANSACTION_TYPES)
        amount = Decimal(rng.randint(10, 80_000)).quantize(Decimal("0.01"))
        rows.append((
            f"TXN{index:010d}", f"C{customer_index:08d}", f"P{rng.randint(1, 120):04d}",
            happened, happened.date(), tx_type, "C" if tx_type in {"DEPOSIT", "INTEREST"} else "D",
            "CNY", amount, branch_id, "SUCCESS",
        ))
    return rows


def build_marketing_relations(settings: Settings, rng: random.Random) -> list[tuple]:
    now = datetime.now().replace(microsecond=0)
    target = min(settings.customers * 3, 30_000)
    pairs: set[tuple[int, int]] = set()
    rows = []
    while len(rows) < target:
        campaign_index = rng.randint(1, settings.campaigns)
        customer_index = rng.randint(1, settings.customers)
        if (campaign_index, customer_index) in pairs:
            continue
        pairs.add((campaign_index, customer_index))
        response = rng.random() < 0.42
        conversion = response and rng.random() < 0.28
        rows.append((
            f"CMP{campaign_index:04d}", f"C{customer_index:08d}",
            now - timedelta(days=rng.randint(0, 240), seconds=rng.randint(0, 86_399)),
            rng.choice(["APP", "SMS", "PHONE"]), response, conversion,
            Decimal(rng.randint(2_000, 200_000)) if conversion else Decimal("0"),
        ))
    return rows


def reset_demo_tables(cursor) -> None:
    # 仅清理本脚本管理的明确表名，不操作账号、任务、历史和审计表。
    for table in ["fct_customer_marketing", "fct_transaction", "fct_product_holding", "dim_marketing_campaign", "dim_customer", "dim_customer_manager"]:
        cursor.execute(f"DELETE FROM {table}")


def main() -> None:
    args = parse_args()
    # 延迟加载数据库驱动，使 `--help` 在尚未创建虚拟环境时也能正常使用。
    try:
        import mysql.connector
    except ModuleNotFoundError as exc:
        raise SystemExit(
            "缺少 mysql-connector-python。请先执行：python -m pip install -r requirements.txt"
        ) from exc
    settings = Settings(args.customers, args.transactions, args.holdings, args.campaigns, args.seed, args.batch_size)
    rng = random.Random(settings.seed)
    connection = mysql.connector.connect(
        host=args.host, port=args.port, database=args.database, user=args.user, password=args.password,
        charset="utf8mb4", autocommit=False,
    )
    try:
        cursor = connection.cursor()
        if args.reset:
            reset_demo_tables(cursor)
            connection.commit()

        managers = build_managers()
        customers = build_customers(settings, rng)
        campaigns = build_campaigns(settings, rng)
        holdings = build_holdings(settings, rng)
        marketing = build_marketing_relations(settings, rng)
        transactions = build_transactions(settings, rng)

        cursor.executemany("INSERT INTO dim_customer_manager VALUES (%s,%s,%s,%s,%s) ON DUPLICATE KEY UPDATE manager_name=VALUES(manager_name),branch_id=VALUES(branch_id),region_code=VALUES(region_code)", managers)
        insert_batches(cursor, """INSERT INTO dim_customer(customer_id,customer_name_masked,gender_code,age,age_band_code,mobile_masked,customer_level_code,vip_flag,risk_level_code,occupation_code,region_code,branch_id,manager_id,total_asset_amount,asset_change_3m_rate,open_date,status_code,snapshot_date,customer_name) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", customers, settings.batch_size)
        insert_batches(cursor, """INSERT INTO dim_marketing_campaign(campaign_id,campaign_name,campaign_type_code,campaign_status_code,product_id,target_customer_segment_code,channel_code,owner_org_id,owner_manager_id,start_time,end_time,budget_amount,target_count) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", campaigns, settings.batch_size)
        insert_batches(cursor, """INSERT INTO fct_product_holding(customer_id,product_id,product_name,product_category_code,holding_amount,market_value_amount,profit_amount,maturity_date,risk_level_code,snapshot_date) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", holdings, settings.batch_size)
        insert_batches(cursor, """INSERT INTO fct_customer_marketing(campaign_id,customer_id,contact_time,contact_channel_code,response_flag,conversion_flag,conversion_amount) VALUES (%s,%s,%s,%s,%s,%s,%s)""", marketing, settings.batch_size)
        insert_batches(cursor, """INSERT INTO fct_transaction(transaction_id,customer_id,product_id,transaction_time,transaction_date,transaction_type_code,debit_credit_flag,currency_code,amount_cny,branch_id,status_code) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""", transactions, settings.batch_size)
        connection.commit()
        print(f"Generated customers={len(customers)}, transactions={len(transactions)}, holdings={len(holdings)}, campaigns={len(campaigns)}, marketing_relations={len(marketing)}, seed={settings.seed}")
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
