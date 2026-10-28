#!/usr/bin/env bash
# speclint / specdiff 的统一入口（纯 Java，harness-contract 模块产出的 *-cli.jar，启动 <0.5s）
#   contract-cli.sh lint [spec...]
#   contract-cli.sh diff base.yaml head.yaml
source "$(dirname "$0")/lib.sh"
JAR=harness-contract/target/harness-contract-1.0.0-cli.jar
# jar 不存在或源码比 jar 新时重建（只构建这一个模块）
if [ ! -f "$JAR" ] || [ -n "$(find harness-contract/src/main harness-contract/pom.xml -newer "$JAR" -type f 2>/dev/null | head -1)" ]; then
  $MVN -q -pl harness-contract package -DskipTests >&2
fi
exec java -jar "$JAR" "$@"
