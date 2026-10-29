#!/usr/bin/env bash
# 数据库迁移检查：
#   1) 已提交的迁移不可修改 / 删除（Flyway 会因 checksum 不一致拒绝启动，且已执行的环境不会重跑）
#   2) MigrationLint：命名、版本单调递增、破坏性语句、NOT NULL 无默认值
#   scripts/harness/migration-check.sh [migration-dir...]   # 缺省 = 本次改动涉及的目录
source "$(dirname "$0")/lib.sh"

BASE="${BASE:-$(git merge-base HEAD origin/main 2>/dev/null || git merge-base HEAD main 2>/dev/null || echo HEAD)}"
dirs=("$@"); [ ${#dirs[@]} -eq 0 ] && mapfile -t dirs < <(changed_migration_dirs)
[ ${#dirs[@]} -eq 0 ] && { echo "migration-check: no migration changes"; exit 0; }

rc=0
for dir in "${dirs[@]}"; do
  # 已提交文件被改 / 删 => 立刻失败（工作区 + 相对 BASE 的提交都算）
  while read -r status file; do
    [ -z "$file" ] && continue
    case "$status" in
      M*|D*|R*) echo "$file: committed migrations are immutable (status $status); add a new V<next>__*.sql instead" >&2; rc=1 ;;
    esac
  done < <({ git diff --name-status HEAD -- "$dir"; git diff --name-status "$BASE" HEAD -- "$dir" 2>/dev/null; } | sort -u)
  # 新增文件 = 不在 BASE 中的 .sql（含未跟踪）
  added=()
  for f in "$dir"/*.sql; do
    [ -e "$f" ] || continue
    git cat-file -e "$BASE:$f" 2>/dev/null || added+=("$(basename "$f")")
  done
  new_list="$(IFS=,; echo "${added[*]:-}")"
  scripts/harness/contract-cli.sh migration-lint "$dir" ${new_list:+--new "$new_list"} || rc=1
done
exit $rc
