#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# 随机本地口令只写到被 Git 忽略的目录，不回显、不覆盖已有数据库凭证。
python3 - <<'PY'
from pathlib import Path
import os,secrets
folder=Path('.local');folder.mkdir(mode=0o700,exist_ok=True)
env=folder/'postgres.env';config=folder/'database.properties'
if env.exists() != config.exists():
    raise SystemExit('本地数据库配置不完整，请恢复 .local 中的原配置；未覆盖现有文件。')
if not env.exists():
    password=secrets.token_urlsafe(32)
    for path,text in [(env,'POSTGRES_PASSWORD='+password+'\n'),(config,'spring.datasource.password=${KNOWLEDGE_DB_PASSWORD:'+password+'}\n')]:
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f:f.write(text)
PY
docker compose up -d --wait
