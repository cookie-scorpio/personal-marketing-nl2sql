"""在隔离模拟库评测实际HTTP行为和SQL结果；标准答案不会发送给模型。"""
from __future__ import annotations
import argparse
import csv
import json
import math
import os
import re
import sys
import time
import uuid
from datetime import datetime
from decimal import Decimal
from pathlib import Path
import requests
import yaml

ROOT = Path(__file__).resolve().parents[2]
TERMINAL = {'SUCCESS', 'FAILED', 'DEGRADED', 'TIMED_OUT', 'CANCELLED', 'ASKING'}
SCOPES = {'manager01': "c.manager_id='M0001'", 'leader01': "c.branch_id='B001'", 'director01': "c.region_code='EAST'"}


def arguments():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--base-url', default='http://127.0.0.1:18081')
    p.add_argument('--database', default='pf_nl2sql_v13_test')
    p.add_argument('--config', type=Path, default=ROOT/'crm/backend/src/main/resources/application-local.yml')
    p.add_argument('--cases', type=Path, default=Path(__file__).with_name('cases-v1.3.json'))
    p.add_argument('--mode', choices=['rules', 'model'], default='rules')
    p.add_argument('--allow-model', action='store_true', help='明确允许该运行调用真实模型；后端须启用共享请求额度文件')
    p.add_argument('--budget-file', type=Path, default=ROOT/'tmp/v13/model-requests.txt')
    p.add_argument('--output', type=Path, default=ROOT/'tmp/v13/evaluation')
    p.add_argument('--sql-log', type=Path, help='测试实例sql-review.log路径；用于核验被拒绝、未进入结果的SQL')
    p.add_argument('--ids', default='', help='逗号分隔的用例编号；省略时运行所选mode全部用例')
    return p.parse_args()


class Client:
    def __init__(self, url, user):
        self.url = url.rstrip('/')
        self.http = requests.Session()
        self.http.trust_env = False  # 仅访问指定本地测试后端，避免系统代理干扰
        payload = self.http.post(self.url+'/api/v1/auth/login', json={'username': user, 'password': os.getenv('EVAL_PASSWORD', 'Demo@123')}, timeout=15).json()
        self.http.headers['Authorization'] = 'Bearer '+payload['data']['access_token']

    def call(self, method, path, body=None, key=None):
        response = self.http.request(method, self.url+path, json=body,
                                     headers={'Idempotency-Key': key or str(uuid.uuid4())}, timeout=20)
        payload = response.json()
        if payload.get('code') != 0:
            raise RuntimeError(str(payload.get('code'))+': '+payload.get('message', 'HTTP failure'))
        return payload['data']


def golden(db, case):
    scope = SCOPES[case['user']]+" AND c.status_code='ACTIVE'"
    with db.cursor() as cursor:
        if case.get('comparator') == 'gender':
            cursor.execute('SELECT c.gender_code,COUNT(*) FROM dim_customer c WHERE '+scope+' GROUP BY c.gender_code')
            return {str(k): int(v) for k, v in cursor.fetchall()}
        if case.get('comparator') == 'deposit':
            # 独立算法按客户余额计算分档，不使用模型SQL或同一分档SQL实现。
            cursor.execute("SELECT c.customer_id,COALESCE(SUM(h.market_value_amount),0) FROM dim_customer c LEFT JOIN fct_product_holding h ON h.customer_id=c.customer_id AND h.product_category_code='DEPOSIT' WHERE "+scope+' GROUP BY c.customer_id')
            upper, step = case['upper'], case['step']
            counts = {i: 0 for i in range(upper//step)}
            for _, amount in cursor.fetchall():
                if 0 <= amount <= upper:
                    counts[min(int(amount//step), upper//step-1)] += 1
            return counts
        if case.get('comparator') == 'transaction':
            cursor.execute("SELECT t.branch_id,COUNT(*),ROUND(SUM(t.amount_cny)/10000,2) FROM fct_transaction t JOIN dim_customer c ON c.customer_id=t.customer_id WHERE "+scope+" AND t.status_code='SUCCESS' AND t.transaction_date>=DATE_SUB(CURRENT_DATE,INTERVAL 30 DAY) GROUP BY t.branch_id")
            return sorted([list(row) for row in cursor.fetchall()])
    return None


def numeric(value):
    return isinstance(value, (int, float, Decimal)) and not isinstance(value, bool)


def compare(case, result, expected):
    rows, columns = result.get('rows', []), result.get('columns', [])
    if not case.get('comparator'):
        return None, '本用例只验证澄清行为'
    count_cols = [c['key'] for c in columns if c.get('role') == 'MEASURE' and (c.get('unit') in ['人', '位'] or 'count' in c['key']) and c.get('unit') != '%']
    dimensions = [c['key'] for c in columns if c.get('role') == 'DIMENSION']
    if case['comparator'] == 'transaction':
        actual = sorted([[r.get('branch_id'), r.get('transaction_count'), r.get('transaction_amount_wan')] for r in rows])
        ok = len(actual) == len(expected) and all(a[0] == e[0] and a[1] == e[1] and abs(float(a[2])-float(e[2])) <= .01 for a, e in zip(actual, expected))
        return ok, '按机构比较交易笔数和金额，金额容差0.01万元'
    if len(count_cols) != 1 or not dimensions:
        return False, '无法唯一识别人数字段及分组字段，需人工核查；不自动判对'
    count_key = count_cols[0]
    if any(not numeric(r.get(count_key)) or not math.isfinite(float(r[count_key])) or float(r[count_key]) < 0 or not float(r[count_key]).is_integer() for r in rows):
        return False, '人数必须为非负整数'

    if case['comparator'] == 'gender':
        labels = {'男': 'M', '男性': 'M', '女': 'F', '女性': 'F', '未知': 'U', '未提供': 'U', '其他': 'U'}
        actual = {labels.get(str(r[dimensions[0]]), str(r[dimensions[0]])): int(r[count_key]) for r in rows}
        return actual == expected, '逐性别比较人数，支持常用中文标签'
    # 优先使用分档标签，排除额外返回的排序号或金额上下界。
    dimension = next((k for k in dimensions if rows and isinstance(rows[0].get(k), str)), dimensions[0])
    starts = []
    for row in rows:
        label = str(row.get(dimension, '')).replace(',', '')
        match = re.search(r'\d+(?:\.\d+)?', label)
        if not match:
            return False, '分档标签无法解析：'+label
        starts.append(float(match.group()))
    # 只接受能映射到预期金额下界的元/万元/亿元标签；序号不是金额，不能静默通过。
    actual = None
    for scale in [1, 10000, 100000000]:
        candidate = {}
        for start, row in zip(starts, rows):
            index = start*scale/case['step']
            if not index.is_integer() or int(index) not in expected or int(index) in candidate:
                break
            candidate[int(index)] = int(row[count_key])
        else:
            candidate = {i: candidate.get(i, 0) for i in expected}
            if candidate == expected:
                actual = candidate
                break
    if actual is None:
        return False, '分档人数或金额标签与独立客户余额算法不一致'
    # 若返回比例，逐组核验分母。饼图可直接使用人数，ECharts按相同人数计算占比。
    rates = [c for c in columns if c.get('unit') == '%' or re.search('ratio|rate|percent|proportion', c['key'])]
    denominator = sum(expected.values())
    for row in rows:
        for col in rates:
            value = row.get(col['key'])
            if denominator == 0:
                if value not in (0, None): return False, '空分母比例不正确'
            elif not numeric(value):
                return False, '比例字段不是数值'
            else:
                rate = int(row[count_key])/denominator
                target = rate*100 if col.get('unit') == '%' or 'percent' in col['key'] else rate
                if abs(float(value)-target) > (.011 if target > 1 or col.get('unit') == '%' else .00011):
                    return False, '分组占比与授权范围内分母不一致'
    return True, '逐档人数、金额标签及已返回比例通过；零人数档允许省略，饼图由人数计算占比'


def run_case(args, db, case):
    client = Client(args.base_url, case['user'])
    expected = golden(db, case)
    session, start = str(uuid.uuid4()), time.monotonic()
    submit = client.call('POST', '/api/v1/queries', {'session_id': session, 'query_text': case['question'], 'thinking_enabled': True, 'preferred_display': 'AUTO'})
    task = submit['task_id']
    # 防止测试库与后端实例指向不同数据库，停止后续确认和评测。
    with db.cursor() as cursor:
        cursor.execute('SELECT COUNT(*) FROM query_task WHERE task_id=%s', (task,))
        if cursor.fetchone()[0] != 1:
            raise RuntimeError('后端不在指定测试库，停止评测')
    while time.monotonic()-start < 420:
        state = client.call('GET', '/api/v1/queries/'+task+'/status')
        if state['status'] == 'CONFIRMING':
            client.call('POST', '/api/v1/queries/'+task+'/confirmations', {'decision': 'CONFIRM', 'confirm_token': state['confirmation']['confirm_token']})
        elif state['status'] in TERMINAL:
            break
        time.sleep(.3)
    else:
        client.call('POST', '/api/v1/queries/'+task+'/cancel')
        state = {'status': 'CLIENT_TIMEOUT', 'message': '超过评测等待时间'}
    behavior = state['status'] == case['expected_status']
    if case.get('question_types'):
        behavior = behavior and state.get('question', {}).get('type') in case['question_types']
    result = state.get('result') or {}
    correct, note = compare(case, result, expected) if state['status'] == 'SUCCESS' else (False if case.get('comparator') and case['expected_status']=='SUCCESS' else None, '未返回可比较结果')
    chart = None
    if case.get('chart'):
        chart = not result.get('charts') if case['chart'] == 'TABLE' else any(c['type'] == case['chart'] for c in result.get('charts', []))
    with db.cursor() as cursor:
        cursor.execute('SELECT sql_text FROM query_task WHERE task_id=%s', (task,))
        saved_sql = cursor.fetchone()[0]
    log_path = args.sql_log or ROOT/'tmp/v13'/('logs-'+args.base_url.rsplit(':', 1)[1])/'sql-review.log'
    reviews = []
    if log_path.exists():
        for line in log_path.read_text(encoding='utf-8').splitlines():
            if task not in line: continue
            try:
                entry = json.loads(line[line.index('{'):])
                if entry.get('task_id') == task: reviews.append(entry)
            except ValueError:
                continue
    generated = [r['sql'] for r in reviews if r.get('sql')]
    actual_sql = result.get('sql_preview') or saved_sql or (generated[-1] if generated else '')
    row = {'case_id': case['id'], 'user': case['user'], 'task_id': task, 'expected_status': case['expected_status'], 'status': state['status'],
           'behavior_correct': behavior, 'result_correct': correct, 'chart_correct': chart,
           'passed': behavior and correct is not False and chart is not False,
           'sql_generated': bool(actual_sql) if log_path.exists() or saved_sql else None,
           'sql_validation_passed': bool(saved_sql), 'sql_executed': any(r.get('phase') == 'EXECUTED' for r in reviews) if log_path.exists() else state['status'] == 'SUCCESS',
           'tool_calls': sum(r.get('phase') == 'TOOL_RESULT' for r in reviews) if log_path.exists() else None,
           'elapsed_seconds': round(time.monotonic()-start, 2), 'comparison_note': note, 'failure_reason': (state.get('error') or {}).get('message', ''),
           'sql': actual_sql, 'row_count': len(result.get('rows', []))}
    if state['status'] == 'ASKING':
        row['clarification'] = state.get('question', {}).get('prompt')
        client.call('POST', '/api/v1/queries/'+task+'/cancel')
    # 保留独立测试库会话和报告，便于复核；不自动删除用户库记录。
    return row


def main():
    # 仅纯比较器单元测试时不需要数据库驱动；实际 HTTP/数据库评测再加载可选依赖。
    try:
        import pymysql
    except ModuleNotFoundError as exc:
        raise SystemExit('缺少 PyMySQL，请先执行：python -m pip install -r scripts/evaluation/requirements.txt') from exc
    args = arguments()
    if not re.fullmatch(r'[a-zA-Z0-9_]+_test', args.database):
        raise SystemExit('只允许名称以_test结尾的隔离数据库')
    if not re.fullmatch(r'http://(?:127\.0\.0\.1|localhost):\d+', args.base_url):
        raise SystemExit('本脚本仅访问显式指定的本地测试后端')
    if args.mode == 'model' and (not args.allow_model or not args.budget_file.exists()):
        raise SystemExit('真实模型评测需要--allow-model及已启用的后端共享请求计数文件')
    health = requests.Session(); health.trust_env = False
    for _ in range(60):
        try:
            if health.get(args.base_url+'/actuator/health',timeout=2).json().get('status') == 'UP': break
        except requests.RequestException: pass
        time.sleep(.5)
    else: raise SystemExit('测试后端尚未就绪，未提交任何测试查询')
    config = yaml.safe_load(args.config.read_text(encoding='utf-8'))['spring']['datasource']
    db = pymysql.connect(host=os.getenv('MYSQL_HOST', '127.0.0.1'), port=int(os.getenv('MYSQL_PORT', '3306')),
                         user=os.getenv('MYSQL_USER', config['username']), password=os.getenv('MYSQL_PASSWORD', config['password']),
                         database=args.database, charset='utf8mb4', autocommit=True)
    cases = json.loads(args.cases.read_text(encoding='utf-8-sig'))
    cases = [c for c in cases if c['mode'] == args.mode and (not args.ids or c['id'] in args.ids.split(','))]
    missing_ids = set(args.ids.split(',')) - {c['id'] for c in cases} if args.ids else set()
    if not cases or missing_ids:
        db.close()
        raise SystemExit('未匹配到测试用例或ID不属于所选mode：'+','.join(sorted(missing_ids)))
    args.output.mkdir(parents=True, exist_ok=True)
    results = []
    before = int(args.budget_file.read_text()) if args.budget_file.exists() else 0
    for case in cases:
        try:
            result = run_case(args, db, case)
        except Exception as error:
            result = {'case_id': case['id'], 'user': case['user'], 'expected_status': case['expected_status'],
                      'status': 'CLIENT_ERROR', 'passed': False, 'behavior_correct': False,
                      'result_correct': False if case.get('comparator') else None,
                      'chart_correct': False if case.get('chart') else None,
                      'sql_validation_passed': None, 'sql_executed': None,
                      'failure_reason': str(error)}
        results.append(result)
        print(case['id'], 'PASS' if result['passed'] else 'FAIL', result.get('status'), flush=True)
        (args.output/'results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2, default=str), encoding='utf-8')
    db.close()
    after = int(args.budget_file.read_text()) if args.budget_file.exists() else 0
    summary = {'timestamp': datetime.now().astimezone().isoformat(), 'mode': args.mode, 'cases': len(results),
               'passed': sum(r['passed'] for r in results), 'model_requests': after-before,
               'model_requests_total': after, 'metrics': {}}
    for field in ['behavior_correct', 'result_correct', 'chart_correct', 'sql_validation_passed', 'sql_executed']:
        applicable = [r for r in results if r.get(field) is not None]
        summary['metrics'][field] = {'passed': sum(bool(r[field]) for r in applicable), 'total': len(applicable),
                                     'rate': sum(bool(r[field]) for r in applicable)/len(applicable) if applicable else None}
    (args.output/'summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding='utf-8')
    fields = sorted(set().union(*(r.keys() for r in results))) if results else ['case_id']
    with (args.output/'results.csv').open('w', newline='', encoding='utf-8-sig') as f:
        writer = csv.DictWriter(f, fieldnames=fields); writer.writeheader(); writer.writerows(results)
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    return 0 if all(r['passed'] for r in results) else 1


if __name__ == '__main__':
    sys.exit(main())
