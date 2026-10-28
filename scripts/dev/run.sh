#!/usr/bin/env bash
# make dev  : 本地起全部真实服务 + gateway(:8080)
# make mock : 用 Prism 按契约起 mock + gateway(:8080) —— 后端未完成时前端照常联调
# 两种模式下前端都只连 http://localhost:8080/api/<svc>，无需任何改动。
set -euo pipefail
cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
MVN="${MVN:-mvn}"
MODE="${1:-dev}"
mapfile -t SVCS < <(scripts/dev/services.sh)

# 退出时结束整个进程组（npx -> sh -> node 的多层子进程也一并回收）
trap 'trap - EXIT INT TERM; kill 0 2>/dev/null' EXIT INT TERM

if [ "$MODE" = dev ]; then
  $MVN -q -B package -DskipTests
else
  $MVN -q -B -pl services/gateway -am package -DskipTests
fi

for line in "${SVCS[@]}"; do
  read -r name port <<<"$line"
  export "$(echo "$name" | tr a-z A-Z)_SVC_URL=http://localhost:$port"
done
for line in "${SVCS[@]}"; do
  read -r name port <<<"$line"
  if [ "$MODE" = mock ]; then
    npx -y @stoplight/prism-cli@5 mock -p "$port" "services/$name/src/main/resources/openapi.yaml" &
  else
    PORT="$port" java -jar "services/$name/target/$name-service-1.0.0.jar" &
  fi
  # 网关上游：GATEWAY_UPSTREAMS_<NAME> 由 Spring 宽松绑定到 gateway.upstreams.<name>
  export "GATEWAY_UPSTREAMS_$(echo "$name" | tr a-z A-Z)=http://localhost:$port"
  echo "  [$MODE] $name -> :$port   http://localhost:8080/api/$name"
done
PORT=8080 java -jar services/gateway/target/gateway-service-1.0.0.jar &
wait
