#!/bin/sh
# 从项目根目录运行教学接收器；文件/令牌只保存在 .local，不能用于生产。
set -eu
cd "$(dirname "$0")/.."
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/outbox-demo-classpath.txt
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$(cat target/outbox-demo-classpath.txt)" scripts/OutboxDemoReceiver.java "$@"
