#!/usr/bin/env bash
# 契约 diff：当前工作区 vs BASE（默认 main 的 merge-base），输出前端可读的变更日志。
#   scripts/harness/contract-diff.sh [spec...]
#   BASE=origin/main scripts/harness/contract-diff.sh
source "$(dirname "$0")/lib.sh"

BASE="${BASE:-$(git merge-base HEAD origin/main 2>/dev/null || git merge-base HEAD main 2>/dev/null || echo HEAD)}"
specs=("$@"); [ ${#specs[@]} -eq 0 ] && specs=($SPEC_GLOB)

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
rc=0
for spec in "${specs[@]}"; do
  echo "## $spec  (vs ${BASE:0:12})"
  if ! git cat-file -e "$BASE:$spec" 2>/dev/null; then
    echo "  [added/changed]  new service contract"; continue
  fi
  git show "$BASE:$spec" > "$tmp/base.yaml"
  scripts/harness/contract-cli.sh diff "$tmp/base.yaml" "$spec" || rc=1
done
exit $rc
