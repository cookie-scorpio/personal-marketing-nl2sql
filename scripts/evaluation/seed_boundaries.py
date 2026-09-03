"""为隔离测试库补充金额边界与一对多持有样例，不修改日常使用的数据库。"""
import argparse
import os
from pathlib import Path
from decimal import Decimal
import pymysql
import yaml

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--database', default='pf_nl2sql_v13_test')
    parser.add_argument('--config', type=Path, default=ROOT/'crm/backend/src/main/resources/application-local.yml')
    args = parser.parse_args()
    if args.database != 'pf_nl2sql_v13_test':
        raise SystemExit('边界样例只能写入pf_nl2sql_v13_test；不会重置已有业务数据')
    config = yaml.safe_load(args.config.read_text(encoding='utf-8'))['spring']['datasource']
    db = pymysql.connect(host=os.getenv('MYSQL_HOST', '127.0.0.1'), port=int(os.getenv('MYSQL_PORT', '3306')), user=config['username'], password=config['password'], database=args.database, charset='utf8mb4')
    balances = ['0','499999.99','500000','999999.99','1000000','1500000','2500000','3500000','4500000','5500000','6000000','6500000','7500000','8500000','9500000','10000000','10000000.01','12000000']
    with db.cursor() as c:
        for i, balance in enumerate(balances):
            customer = 'C%08d' % (91000001+i)
            name = '方验'+'甲乙丙丁戊己庚辛壬癸子丑寅卯辰巳午未'[i]
            c.execute("""INSERT INTO dim_customer(customer_id,customer_name,customer_name_masked,gender_code,age,age_band_code,mobile_masked,customer_level_code,vip_flag,risk_level_code,occupation_code,region_code,branch_id,manager_id,total_asset_amount,asset_change_3m_rate,open_date,status_code,snapshot_date)
              VALUES(%s,%s,'方**','M',35,'A26_35','90000000000','NORMAL',FALSE,'R2','TECH','EAST','B001','M0001',12345,0,CURRENT_DATE,'ACTIVE',CURRENT_DATE)
              ON DUPLICATE KEY UPDATE customer_name=VALUES(customer_name),total_asset_amount=12345""", (customer,name))
            if Decimal(balance) > 0:
                first = (Decimal(balance)*Decimal('.6')).quantize(Decimal('.01'))
                for j, amount in enumerate([first, Decimal(balance)-first]):
                    product = 'V13_BOUNDARY_%02d_%d' % (i,j)
                    c.execute("SELECT holding_id FROM fct_product_holding WHERE customer_id=%s AND product_id=%s",(customer,product))
                    if not c.fetchone():
                        c.execute("""INSERT INTO fct_product_holding(customer_id,product_id,product_name,product_category_code,holding_amount,market_value_amount,profit_amount,risk_level_code,snapshot_date)
                         VALUES(%s,%s,'虚构存款边界样例','DEPOSIT',1,%s,0,'R1',CURRENT_DATE)""",(customer,product,amount))
        # 对权限外的大额客户按相同编号规则固定添加，保证越权统计会改变答案。
        c.execute("""INSERT INTO dim_customer(customer_id,customer_name,customer_name_masked,gender_code,age,age_band_code,mobile_masked,customer_level_code,vip_flag,risk_level_code,occupation_code,region_code,branch_id,manager_id,total_asset_amount,asset_change_3m_rate,open_date,status_code,snapshot_date)
          VALUES('C91999999','方验外','方**','F',35,'A26_35','90099999999','NORMAL',FALSE,'R2','TECH','SOUTH','B008','M0024',99000000,0,CURRENT_DATE,'ACTIVE',CURRENT_DATE)
          ON DUPLICATE KEY UPDATE customer_id=VALUES(customer_id)""")
    db.commit();db.close();print('已准备18名金额边界客户及1名权限外客户，保留其他数据。')


if __name__ == '__main__':
    main()
