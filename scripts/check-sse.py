#!/usr/bin/env python3
"""真实本机 SSE 验收：状态订阅会创建并结束一条教学会话；--model 额外调用真实模型一次。"""
import argparse
import json
from pathlib import Path
import time
import urllib.request
from handoff_client import Client


def events(response):
    """逐行读取协议事件；urllib 处理 HTTP 分块，不把网络块直接当 JSON。"""
    name, data = '', []
    for raw in response:
        line = raw.decode('utf-8').rstrip('\r\n')
        if not line:
            if data:
                yield name, json.loads('\n'.join(data))
            name, data = '', []
        elif line.startswith('event:'):
            name = line[6:].lstrip(' ')
        elif line.startswith('data:'):
            data.append(line[5:].removeprefix(' '))


def stream(client, path, question=None, last_id=None):
    headers = {'Accept': 'text/event-stream', 'X-CSRF-TOKEN': client.csrf}
    if last_id is not None:
        headers['Last-Event-ID'] = last_id
    payload = None
    if question is not None:
        headers['Content-Type'] = 'application/json'
        payload = json.dumps({'question': question}).encode()
    response = client.opener.open(urllib.request.Request(client.base + path, data=payload, headers=headers), timeout=45)
    assert response.headers['Content-Type'].startswith('text/event-stream')
    assert response.headers['X-Accel-Buffering'] == 'no'
    return response


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', action='store_true')
    parser.add_argument('--base', choices=['http://127.0.0.1:18080', 'http://127.0.0.1:18081'], default='http://127.0.0.1:18080')
    parser.add_argument('--output', default='/tmp/ch18-sse-results.json')
    args = parser.parse_args()
    # 仅允许已知本机端口，不能把账户发到调用方任意指定的服务器。
    Client.base = args.base
    customer, agent = Client('customer1001'), Client('support9001')
    cid = customer.call('/api/handoff/conversations', 'POST')['conversationId']
    path = f'/api/handoff/conversations/{cid}/events'
    states = []
    with stream(customer, path) as response:
        incoming = events(response)
        def state(expected):
            name, value = next(incoming)
            assert name == 'conversation.state' and value['mode'] == expected
            states.append({'mode': value['mode'], 'version': value['version']})
        state('BOT')
        customer.call(f'/api/handoff/conversations/{cid}/handoff', 'POST')
        state('WAITING_HUMAN')
        agent.call(f'/api/support/conversations/{cid}/accept', 'POST')
        state('HUMAN_ACTIVE')
        agent.call(f'/api/support/conversations/{cid}/close', 'POST')
        state('CLOSED')
        assert list(incoming) == []
    with stream(customer, path, last_id='999999') as response:
        replay = list(events(response))
        assert len(replay) == 1 and replay[0][1]['mode'] == 'CLOSED'
    model = None
    if args.model:
        start = time.monotonic()
        fragments, output, first, last_sequence, turn_id, terminal = 0, [], None, 0, None, None
        with stream(customer, '/internal/stream-lab/answer', '用三行解释 SSE，每行说明一个要点，保留换行。') as response:
            for name, value in events(response):
                assert value['sequence'] == last_sequence + 1
                last_sequence = value['sequence']
                if turn_id is None:
                    turn_id = value['turnId']
                assert turn_id == value['turnId'] and terminal is None
                if name == 'answer.delta':
                    fragments += 1
                    output.append(value['text'])
                    if first is None:
                        first = round((time.monotonic() - start) * 1000)
                if name in ('turn.completed', 'turn.failed'):
                    terminal = name
        elapsed = round((time.monotonic() - start) * 1000)
        assert terminal == 'turn.completed' and fragments > 1 and first < elapsed
        model = {'turnId': turn_id, 'deltas': fragments, 'firstFragmentMs': first, 'elapsedMs': elapsed,
                 'terminal': terminal, 'characters': len(''.join(output)), 'hasNewline': '\n' in ''.join(output)}
    result = {'base': args.base, 'conversationId': cid, 'statesOverOneConnection': states,
              'reconnect': 'CURRENT_SNAPSHOT_ONLY', 'model': model}
    Path(args.output).write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
