#!/usr/bin/env python3
"""初始化第十七章本地教学账户；只创建不存在的文件，不覆盖或轮换已有密码，不输出凭证。"""
import os
import pathlib
import secrets

target = pathlib.Path(__file__).resolve().parents[1] / '.local/handoff-accounts.properties'
target.parent.mkdir(exist_ok=True)
try:
    fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
except FileExistsError:
    print('已有本地账户文件，保持不变。')
else:
    with os.fdopen(fd, 'w') as stream:
        stream.write('# 本地教学专用。密码只存本机；不要提交、截图或发送此文件。\n')
        for username in ('customer1001', 'customer2002', 'support9001', 'support9002'):
            stream.write(f'handoff.accounts.{username}.password={secrets.token_urlsafe(24)}\n')
    print('本地账户已生成，请在 .local/handoff-accounts.properties 查看。文件权限 600。')
