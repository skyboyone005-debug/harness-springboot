#!/usr/bin/env bash
# PostToolUse：每次编辑后的即时反馈（只做秒级检查，测试留给 Stop 阶段）。
#   *.java                 -> 编译所在模块
#   openapi.yaml           -> speclint + 注入"下一步"提示
#   db/migration/*.sql     -> migration-lint
# exit 2 => stderr 回灌给 Agent，让它在同一轮内立即修复。
source "$(dirname "$0")/lib.sh"
read_hook_input

f="$(relpath "$(json_get '.tool_input.file_path')")"
[ -f "$f" ] || exit 0
t0="$(now_ms)"
record() { metric edit.check "kind=$1" "result=$2" "file=$f" "ms=$(( $(now_ms) - t0 ))"; }

case "$f" in
  *.java)
    mod="$(module_of "$f")"; [ -n "$mod" ] || exit 0
    if ! out="$($MVN -q -pl "$mod" -am compile 2>&1)"; then
      record compile fail
      echo "compile failed after editing $f:" >&2
      echo "$out" | grep -E '\[ERROR\].*\.java|ERROR.*symbol|ERROR.*incompatible' | awk '!seen[$0]++' | head -15 >&2
      exit 2
    fi
    record compile pass
    ;;
  services/*/src/main/resources/openapi.yaml)
    if ! out="$(scripts/harness/contract-cli.sh lint "$f" 2>&1)"; then
      record speclint fail; echo "$out" | tail -20 >&2; exit 2
    fi
    record speclint pass
    svc="$(echo "$f" | cut -d/ -f2)"
    msg="契约 $f 已修改。下一步：1) 更新 services/$svc 的 Controller/DTO 与契约测试 2) make sdk 3) make contract-diff 查看是否破坏性变更 4) 用 fe-handoff skill 生成前端交接说明。"
    printf '{"hookSpecificOutput":{"hookEventName":"PostToolUse","additionalContext":"%s"}}\n' "$msg"
    ;;
  services/*/src/main/resources/db/migration/*)
    if ! out="$(scripts/harness/migration-check.sh "$(dirname "$f")" 2>&1)"; then
      record migration fail; echo "$out" | grep -v '^migration-lint: .* OK' | tail -20 >&2; exit 2
    fi
    record migration pass
    ;;
esac
exit 0
