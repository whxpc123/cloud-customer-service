#!/usr/bin/env bash
# 命令失败、未定义变量或管道失败时立即退出，避免带着半套配置继续启动。
set -euo pipefail
# 从脚本自身位置定位项目根目录，保证 .local 和 compose.yml 路径不依赖调用者所在目录。
cd "$(dirname "$0")/.."
# 随机本地口令只写到被 Git 忽略的目录，不回显、不覆盖已有数据库凭证。
python3 - <<'PY'
from pathlib import Path
import os,secrets
# .local 只供当前用户读取；两个文件分别服务 Docker 与 Spring，必须保持同一口令。
folder=Path('.local');folder.mkdir(mode=0o700,exist_ok=True)
env=folder/'postgres.env';config=folder/'database.properties'
# 发现只剩一个文件时停止，不生成新口令覆盖原数据库的连接信息。
if env.exists() != config.exists():
    raise SystemExit('本地数据库配置不完整，请恢复 .local 中的原配置；未覆盖现有文件。')
if not env.exists():
    # 仅首次运行生成随机口令，之后复用；不要把口令输出到终端或写入共享 IDEA 配置。
    password=secrets.token_urlsafe(32)
    for path,text in [(env,'POSTGRES_PASSWORD='+password+'\n'),(config,'spring.datasource.password=${KNOWLEDGE_DB_PASSWORD:'+password+'}\n')]:
        # O_EXCL 防止覆盖已存在文件，0600 将读取权限限制为当前用户。
        fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
        with os.fdopen(fd,'w') as f:f.write(text)
PY
# 创建或复用数据库容器并等待就绪，数据保存在 Compose 命名卷中。
docker compose up -d --wait
