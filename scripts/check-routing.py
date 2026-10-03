#!/usr/bin/env python3
"""第十六章真实分类验收。显式执行才会访问本地应用并消耗其模型额度；不查询订单、不修改知识库。"""
import argparse
import http.cookiejar
import json
import pathlib
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', default='/tmp/ch16-routing-results.json', help='逐题真实结果写入此处，不含 API Key')
    args = parser.parse_args()
    # 固定回环地址，避免将会话令牌或问题发给未知主机。
    base = 'http://127.0.0.1:18080/internal/routing'
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
    with opener.open(base + '/session', timeout=10) as response:
        token = json.load(response)['csrfToken']
    cases = json.loads((pathlib.Path(__file__).resolve().parents[1] / 'src/test/resources/routing-cases.json').read_text())
    results = []
    for case in cases:
        start = time.monotonic()
        request = urllib.request.Request(base + '/decide', data=json.dumps({'message': case['message']}).encode(),
                                         headers={'Content-Type': 'application/json', 'X-Routing-CSRF': token})
        try:
            with opener.open(request, timeout=90) as response:
                result = json.load(response)
            passed = all(result['decision'].get(key) == value for key, value in case.items() if key != 'message')
            expected_calls = 0 if result['decision']['source'] == 'RULE' else 1
            passed = passed and result['routing']['classifierCalls'] == expected_calls
        except Exception as error:
            result, passed = {'errorType': type(error).__name__}, False
        results.append({'case': case, 'passed': passed, 'elapsedMs': round((time.monotonic()-start)*1000), 'actual': result})
        print(json.dumps(results[-1], ensure_ascii=False), flush=True)
    pathlib.Path(args.output).write_text(json.dumps(results, ensure_ascii=False, indent=2))
    count = sum(row['passed'] for row in results)
    print(f'固定样本匹配 {count}/{len(results)}；这是本次小样本结果，不是总体正确率。')
    return 0 if count == len(results) else 1


if __name__ == '__main__':
    raise SystemExit(main())
