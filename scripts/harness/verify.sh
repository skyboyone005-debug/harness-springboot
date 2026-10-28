#!/usr/bin/env bash
# 增量质量门：只检查本次改动影响的模块。
#   scripts/harness/verify.sh          # 人 / Codex / git pre-commit
#   scripts/harness/verify.sh --hook   # Claude Code Stop hook（失败则阻止结束，并把错误摘要回灌）
#   scripts/harness/verify.sh --all    # CI：全量
source "$(dirname "$0")/lib.sh"

MODE="${1:-}"
if [ "$MODE" = "--hook" ]; then
  read_hook_input
  # 防止 Stop hook 死循环：本轮已被拦过一次就放行，交给人类
  [ "$(json_get '.stop_hook_active')" = "true" ] && exit 0
fi

if [ "$MODE" = "--all" ]; then
  mods=ALL; specs="$(ls $SPEC_GLOB)"
else
  mods="$(affected_modules | tr '\n' ' ' | sed 's/ *$//')"
  specs="$(changed_specs)"
fi

if [ -z "$mods" ] && [ -z "$specs" ]; then
  [ "$MODE" = "--hook" ] || echo "verify: nothing changed"
  exit 0
fi

LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
# 失败摘要：先给关键行（Agent 最需要的信号），再给少量尾部上下文，控制回灌 token
summary() {
  grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)|violates contract|not declared|contract coverage|expected:|\[ERROR\] .*\.java|BREAKING|speclint:|problem' "$LOG" \
    | grep -vE 'Tests run: .*, Failures: 0, Errors: 0' | head -20
  echo "--- tail ---"; grep -vE '^\[ERROR\] *$|Help 1|re-run Maven|full stack trace|For more information' "$LOG" | tail -12
}
fail() {
  if [ "$MODE" = "--hook" ]; then
    { echo "verify FAILED at step [$1]. Fix the root cause before finishing (do not weaken tests or contracts):"; summary; } >&2
    exit 2
  fi
  c_red "✗ $1"; summary; exit 1
}
step() { local name="$1"; shift; [ "$MODE" = "--hook" ] || echo "→ $name"; "$@" >"$LOG" 2>&1 || fail "$name"; }

# 契约检查在前：契约错误是根因，先报
if [ -n "$specs" ]; then
  step "speclint"      scripts/harness/contract-cli.sh lint $specs
  step "contract-diff" scripts/harness/contract-diff.sh $specs
fi
if [ "$mods" = "ALL" ]; then
  step "mvn verify (all modules)" $MVN -q -B verify
elif [ -n "$mods" ]; then
  step "mvn test ($mods)" $MVN -q -B -pl "$(echo $mods | tr ' ' ',')" -am test
fi

[ "$MODE" = "--hook" ] || c_green "✓ verify passed"
exit 0
