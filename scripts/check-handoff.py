#!/usr/bin/env python3
"""显式验收本机人工交接：会创建并结束一条教学会话。--model 额外调用真实路由模型；--verify-restart 只读验收记录。"""
import argparse
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import threading
import urllib.error
import uuid
from handoff_client import Client


def expect_http(status, call):
    try:
        call()
    except urllib.error.HTTPError as error:
        assert error.code == status, (error.code, status)
    else:
        raise AssertionError(f'预期 HTTP {status}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', action='store_true')
    parser.add_argument('--verify-restart', action='store_true')
    parser.add_argument('--output', default='/tmp/ch17-handoff-results.json')
    args = parser.parse_args()
    customer = Client('customer1001')
    if args.verify_restart:
        saved = json.loads(Path(args.output).read_text())
        history = customer.call(f"/api/handoff/conversations/{saved['conversationId']}/messages")
        assert history['receipt'] == saved['receipt']
        assert [m['id'] for m in history['messages']] == saved['messageIds']
        print('重启后重新登录：同一会话、受理编号、接待状态、版本、正式消息序号均保留。')
        return
    other = Client('customer2002')
    agents = [Client('support9001'), Client('support9002')]
    created = customer.call('/api/handoff/conversations', 'POST')
    cid = created['conversationId']
    path = f'/api/handoff/conversations/{cid}'
    chat = f'/internal/routing/conversations/{cid}/messages'
    message_id = str(uuid.uuid4())
    hello = customer.call(chat, 'POST', {'message': '你好', 'clientMessageId': message_id})
    assert hello['published'] and hello['message']['payload']['decision']['route'] == 'SMALL_TALK'
    assert customer.call(chat, 'POST', {'message': '你好', 'clientMessageId': message_id})['deliveryStatus'] == 'REPLAY'
    model_route = None
    if args.model:
        result = customer.call(chat, 'POST', {'message': 'A10001 发货了吗？', 'clientMessageId': str(uuid.uuid4())})
        model_route = result['message']['payload']['decision']['route']
        assert result['published'] and model_route == 'ORDER_QUERY'
    requested = customer.call(path + '/handoff', 'POST')
    assert requested == customer.call(path + '/handoff', 'POST')
    assert requested['mode'] == 'WAITING_HUMAN'
    assert customer.call(chat, 'POST', {'message': '交接验收：补充说明', 'clientMessageId': str(uuid.uuid4())})['deliveryStatus'] == 'USER_SAVED'
    expect_http(404, lambda: other.call(path + '/messages'))
    expect_http(403, lambda: customer.call('/api/support/queue'))
    assert any(r['conversationId'] == cid for r in agents[0].call('/api/support/queue'))
    barrier = threading.Barrier(2)
    def claim(index):
        barrier.wait(timeout=10)
        try:
            return index, agents[index].call(f'/api/support/conversations/{cid}/accept', 'POST')
        except urllib.error.HTTPError as error:
            assert error.code == 409
            return index, None
    with ThreadPoolExecutor(max_workers=2) as pool:
        claims = list(pool.map(claim, range(2)))
    winners = [(index, receipt) for index, receipt in claims if receipt]
    assert len(winners) == 1
    winner, accepted = winners[0]
    assert accepted == agents[winner].call(f'/api/support/conversations/{cid}/accept', 'POST')
    assert agents[winner].call(f'/api/support/conversations/{cid}/messages')['messages']
    assert customer.call(chat, 'POST', {'message': '接待状态下的验收记录', 'clientMessageId': str(uuid.uuid4())})['deliveryStatus'] == 'USER_SAVED'
    expect_http(409, lambda: agents[1-winner].call(f'/api/support/conversations/{cid}/close', 'POST'))
    closed = agents[winner].call(f'/api/support/conversations/{cid}/close', 'POST')
    assert closed == agents[winner].call(f'/api/support/conversations/{cid}/close', 'POST')
    assert customer.call(path + '/handoff', 'POST') == closed and closed['mode'] == 'CLOSED'
    expect_http(409, lambda: customer.call(chat, 'POST', {'message': '结束后不能继续', 'clientMessageId': str(uuid.uuid4())}))
    history = customer.call(path + '/messages')
    result = {'conversationId': cid, 'receipt': closed, 'messageIds': [m['id'] for m in history['messages']],
              'realModelRoute': model_route, 'concurrentClaimWinners': len(winners), 'checks': 'PASSED'}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
