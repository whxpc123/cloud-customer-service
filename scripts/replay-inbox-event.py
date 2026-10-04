#!/usr/bin/env python3
"""本机只回放已在接收库成功处理的教学事件；不创建新事件，不补发历史 Outbox。"""
import json
import subprocess
import sys
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parent.parent
# UUID 解析后再嵌入受控 SQL；不接收任意 SQL、端点或用户正文。
event_id = str(uuid.UUID(sys.argv[1])) if len(sys.argv) == 2 else None
if not event_id:
    raise SystemExit('用法：python3 scripts/replay-inbox-event.py 已成功处理的eventId')


def query(database, statement):
    """只查询指定本机实例，错误响应不包含令牌。"""
    return subprocess.check_output([
        'docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-X', '-qAt', '-v', 'ON_ERROR_STOP=1',
        '-U', 'cloud_ai', '-d', database, '-c', statement
    ], cwd=root, text=True).strip()


known = query('after_sale_receiver', f"""
    SELECT count(*) FROM rx_inbox WHERE producer_id='yunshan-customer-service'
    AND tenant_id='tenant-yunshan' AND event_id='{event_id}' AND status='PROCESSED'
""")
if known != '1':
    raise SystemExit('接收端没有该事件的成功记录；此工具只回放，不用于首次投递或补发。')
body = query('cloud_customer_service', f"""
    SELECT payload::text FROM ai.cs_outbox WHERE event_id='{event_id}' AND status='DELIVERED'
""")
if not body:
    raise SystemExit('发送端没有该事件的已确认记录；未执行网络请求。')
properties = dict(line.split('=', 1) for line in (root / '.local/inbox-sender.properties').read_text().splitlines() if '=' in line)
endpoint = 'http://127.0.0.1:18083/integration/after-sales/applications'
if properties.get('REMOTE_AFTER_SALE_ENDPOINT') != endpoint:
    raise SystemExit('此工具只支持固定本机 18083 端点；未发送。')
# 禁用系统 HTTP 代理，令牌和正文只发送到已校验的回环地址；重定向不能携带凭证离开本机。
class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
receipts = []
for attempt in range(3):
    request = urllib.request.Request(endpoint, data=body.encode('utf-8'), method='POST', headers={
        'Authorization': 'Bearer ' + properties['REMOTE_AFTER_SALE_TOKEN'],
        'Content-Type': 'application/json', 'Idempotency-Key': event_id
    })
    with opener.open(request, timeout=10) as response:
        receipt = json.load(response)
        if receipt.get('eventId') != event_id or receipt.get('status') != 'PERSISTED':
            raise SystemExit('回执不匹配；停止。')
        receipts.append(receipt)
        print(f"第{attempt+1}次：HTTP {response.status}，remoteApplicationId={receipt['remoteApplicationId']}")
if receipts[1:] != [receipts[0], receipts[0]]:
    raise SystemExit('三次回执不一致，请核查。')
count = query('after_sale_receiver', f"""
    SELECT count(*) FROM rx_after_sale_application WHERE producer_id='yunshan-customer-service'
    AND tenant_id='tenant-yunshan' AND source_event_id='{event_id}'
""")
if count != '1':
    raise SystemExit('接收方申请数量异常，请核查。')
print('验收通过：三次相同回执，接收方始终只有 1 份申请。')
