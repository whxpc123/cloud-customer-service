#!/usr/bin/env bash
# 独立数据库与独立登录角色；复用同一 PostgreSQL 实例，但不修改发送方的任何业务表。
set -euo pipefail
cd "$(dirname "$0")/.."
./scripts/start-knowledge-db.sh
python3 - <<'PY'
from pathlib import Path
import os,secrets,subprocess
folder=Path('.local');config=folder/'receiver-database.properties'
container=subprocess.check_output(['docker','compose','ps','-q','postgres'],text=True).strip()
def sql(query):
    return subprocess.check_output(['docker','exec','-i',container,'psql','-X','-qAt','-v','ON_ERROR_STOP=1','-U','cloud_ai','-d','postgres'],input=query,text=True).strip()
role=sql("SELECT count(*) FROM pg_roles WHERE rolname='after_sale_receiver';")
db=sql("SELECT count(*) FROM pg_database WHERE datname='after_sale_receiver';")
if not config.exists():
    if role!='0' or db!='0':raise SystemExit('已有接收数据库或角色，但缺少原凭证文件；请恢复 .local，未覆盖或重置口令。')
    password=secrets.token_hex(32)
    fd=os.open(config,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
    with os.fdopen(fd,'w') as f:
        f.write('RECEIVER_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/after_sale_receiver\nRECEIVER_DB_USER=after_sale_receiver\nRECEIVER_DB_PASSWORD='+password+'\n')
props=dict(line.split('=',1) for line in config.read_text().splitlines())
password=props['RECEIVER_DB_PASSWORD']
if not all(c in '0123456789abcdef' for c in password) or len(password)!=64:raise SystemExit('接收配置不符合本机脚本格式；未修改数据库。')
if role=='0':sql("CREATE ROLE after_sale_receiver LOGIN PASSWORD '"+password+"';")
if db=='0':sql('CREATE DATABASE after_sale_receiver OWNER after_sale_receiver;')
# 不改变发送方数据库的 CONNECT 授权；接收角色没有发送方表的读写权限。
print('独立接收数据库 after_sale_receiver 已就绪。')
PY
"${JAVA_HOME:+$JAVA_HOME/bin/}java" scripts/LocalServiceToken.java "$PWD"
