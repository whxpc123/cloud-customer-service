#!/usr/bin/env bash
# 首次先执行 prepare-receiver-local.sh；此脚本不生成/打印新口令，也不修改既有事件。
set -euo pipefail
cd "$(dirname "$0")/.."
test -f .local/receiver-database.properties && test -f .local/receiver-jwt.properties || {
  echo '请先运行 ./scripts/prepare-receiver-local.sh'; exit 1;
}
./mvnw -f after-sale-receiver/pom.xml -q -DskipTests package
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" '-DsocksNonProxyHosts=localhost|127.*|[::1]' \
  -jar after-sale-receiver/target/after-sale-receiver-0.0.1-SNAPSHOT.jar \
  --spring.profiles.active=local-jwt \
  --spring.config.additional-location=file:.local/receiver-database.properties,file:.local/receiver-jwt.properties
