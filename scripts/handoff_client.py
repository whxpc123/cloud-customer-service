#!/usr/bin/env python3
"""第十七章本机 API 客户端：从忽略的账号文件登录并携带 Cookie/CSRF，从不输出口令。"""
import http.cookiejar
import json
from pathlib import Path
import urllib.parse
import urllib.request


class Client:
    """只访问固定本机服务，不把账户、Cookie 或 CSRF 发送到外部主机。"""
    base = 'http://127.0.0.1:18080'

    def __init__(self, username):
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        session = self.call('/internal/handoff/session')
        self.csrf = session['csrfToken']
        path = Path(__file__).resolve().parents[1] / '.local/handoff-accounts.properties'
        props = dict(line.split('=', 1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))
        key = f'handoff.accounts.{username}.password'
        if key not in props:
            raise ValueError('未配置本地账户，请先运行 scripts/setup-handoff-accounts.py')
        body = urllib.parse.urlencode({'username': username, 'password': props[key]}).encode()
        req = urllib.request.Request(self.base + '/internal/handoff/login', data=body,
            headers={'Content-Type': 'application/x-www-form-urlencoded', 'X-CSRF-TOKEN': self.csrf})
        with self.opener.open(req, timeout=10) as response:
            if response.status != 204:
                raise RuntimeError('登录未成功')
        # 登录后旧 CSRF 失效，读取新令牌；password 不保存为实例字段。
        self.csrf = self.call('/internal/handoff/session')['csrfToken']

    def call(self, path, method='GET', data=None):
        if not path.startswith(('/internal/', '/api/')) or '://' in path:
            raise ValueError('仅允许本机应用 API 路径')
        request = urllib.request.Request(self.base + path, method=method,
            data=None if data is None else json.dumps(data, ensure_ascii=False).encode(),
            headers={'Content-Type': 'application/json', **({'X-CSRF-TOKEN': self.csrf} if self.csrf else {})})
        with self.opener.open(request, timeout=120) as response:
            return None if response.status == 204 else json.load(response)
