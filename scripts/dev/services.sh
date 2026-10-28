#!/usr/bin/env bash
# 服务注册表：services/ 下除 gateway 外的目录即业务服务，端口按字母序从 8081 分配（gateway 固定 8080）。
# 输出: "<name> <port>"
i=0
for d in services/*/; do
  n="$(basename "$d")"; [ "$n" = gateway ] && continue
  echo "$n $((8081 + i))"; i=$((i + 1))
done
