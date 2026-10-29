#!/usr/bin/env bash
# 增量质量门：只检查本次改动影响的模块。
#   scripts/harness/verify.sh          # 人 / Codex / git pre-commit
#   scripts/harness/verify.sh --hook   # Claude Code Stop hook（失败则阻止结束，并把错误摘要回灌）
#   scripts/harness/verify.sh --all    # CI：全量
source "$(dirname "$0")/lib.sh"

MODE="${1:-}"
if [ "$MODE" = "--hook" ]; then
  read_hook_input
  # 防止 Stop hook 死循环：本轮已被拦过一次就放行，交给人类（记为一次"升级到人工"）
  if [ "$(json_get '.stop_hook_active')" = "true" ]; then metric verify.escalate; exit 0; fi
fi

if [ "$MODE" = "--all" ]; then
  mods=ALL; specs="$(ls $SPEC_GLOB)"; mig_dirs="$(ls -d services/*/src/main/resources/db/migration 2>/dev/null || true)"
else
  mods="$(affected_modules | tr '\n' ' ' | sed 's/ *$//')"
  specs="$(changed_specs)"; mig_dirs="$(changed_migration_dirs)"
fi

if [ -z "$mods" ] && [ -z "$specs" ] && [ -z "$mig_dirs" ]; then
  [ "$MODE" = "--hook" ] || echo "verify: nothing changed"
  exit 0
fi

LOG="$(mktemp)"; trap 'rm -f "$LOG"' EXIT
T0="$(now_ms)"
# 失败摘要：先给关键行（Agent 最需要的信号），再给少量尾部上下文，控制回灌 token
summary() {
  grep -E 'Tests run:.*(Failures: [1-9]|Errors: [1-9])|<<< (FAILURE|ERROR)|violates contract|not declared|contract coverage|expected:|\[ERROR\] .*\.java|BREAKING|speclint:|migration-lint|immutable|destructive statement|problem|^ +- \$' "$LOG" \
    | grep -vE 'Tests run: .*, Failures: 0, Errors: 0| OK$' | head -20
  echo "--- tail ---"
  grep -vE '^\[ERROR\] *$|Help 1|[Rr]e-run Maven|full stack trace|For more information|Please refer|-rf :|After correcting|^WARNING|Mockito is currently|^\[INFO\]' "$LOG" | tail -8
}
fail() {
  metric verify "mode=${MODE:---cli}" result=fail "step=$1" "modules=$mods" "ms=$(( $(now_ms) - T0 ))"
  if [ "$MODE" = "--hook" ]; then
    { echo "verify FAILED at step [$1]. Fix the root cause before finishing (do not weaken tests or contracts):"; summary; } >&2
    exit 2
  fi
  c_red "✗ $1"; summary; exit 1
}
step() { local name="$1"; shift; [ "$MODE" = "--hook" ] || echo "→ $name"; "$@" >"$LOG" 2>&1 || fail "$name"; }

# 契约 / 迁移检查在前：它们是根因，且秒级
if [ -n "$specs" ]; then
  step "speclint"      scripts/harness/contract-cli.sh lint $specs
  step "contract-diff" scripts/harness/contract-diff.sh $specs
fi
if [ -n "$mig_dirs" ]; then
  step "migration-check" scripts/harness/migration-check.sh $mig_dirs
fi
if [ "$mods" = "ALL" ]; then
  step "mvn verify (all modules)" $MVN -q -B verify
elif [ -n "$mods" ]; then
  step "mvn test ($mods)" $MVN -q -B -pl "$(echo $mods | tr ' ' ',')" -am test
fi

metric verify "mode=${MODE:---cli}" result=pass "modules=$mods" "ms=$(( $(now_ms) - T0 ))"
[ "$MODE" = "--hook" ] || c_green "✓ verify passed"
exit 0
