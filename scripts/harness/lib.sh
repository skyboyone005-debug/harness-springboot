#!/usr/bin/env bash
# 共享函数。所有 harness 逻辑都在 scripts/ 里，Claude Code hooks / Codex / git hooks / CI 只是不同的"调用方"。
set -euo pipefail

ROOT="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$ROOT"
MVN="${MVN:-mvn}"                    # 可用 MVN="mvn -o" 加速（离线）或 ./mvnw
SPEC_GLOB='services/*/src/main/resources/openapi.yaml'
MIGRATION_RE='^services/[^/]+/src/main/resources/db/migration/[^/]+$'

# 读取 hook 输入 JSON 的字段：json_get '.tool_input.file_path'
HOOK_INPUT=""
read_hook_input() {
  if [ ! -t 0 ]; then HOOK_INPUT="$(cat)"; fi
  # 有 hook 输入 = 由 Agent 触发；会话 id 用于度量"一次通过率""修复轮数"
  if [ -n "$HOOK_INPUT" ]; then
    HARNESS_SOURCE="${HARNESS_SOURCE:-claude}"
    HARNESS_SESSION="$(json_get '.session_id')"
  fi
}
json_get() {
  [ -z "$HOOK_INPUT" ] && { echo ""; return; }
  if command -v jq >/dev/null 2>&1; then
    printf '%s' "$HOOK_INPUT" | jq -r "$1 // empty"
  else
    printf '%s' "$HOOK_INPUT" | python3 -c "import json,sys
d=json.load(sys.stdin)
for k in sys.argv[1].lstrip('.').split('.'):
    d=d.get(k) if isinstance(d,dict) else None
print('' if d is None else (str(d).lower() if isinstance(d,bool) else d))" "$1"
  fi
}

relpath() { local p="$1"; echo "${p#"$ROOT"/}"; }

# 改动文件（已暂存 + 未暂存 + 未跟踪 + 相对 BASE 的提交）
changed_files() {
  {
    git diff --name-only HEAD 2>/dev/null || true
    git ls-files --others --exclude-standard 2>/dev/null || true
    if [ -n "${BASE:-}" ]; then git diff --name-only "$BASE"...HEAD 2>/dev/null || true; fi
  } | sort -u
}

# 文件 -> 所属 Maven 模块（services/<svc> | harness-contract | common-web）
module_of() {
  case "$1" in
    services/*/*) echo "$1" | cut -d/ -f1-2 ;;
    harness-contract/*|common-web/*) echo "${1%%/*}" ;;
  esac
}

# 受影响模块：改了共享模块或根 pom => 全量（输出 ALL），否则只列被改的服务
affected_modules() {
  local files; files="$(changed_files)"
  [ -z "$files" ] && return 0
  if echo "$files" | grep -qE '^(pom\.xml|common-web/|harness-contract/)'; then echo ALL; return; fi
  { echo "$files" | grep -E '^services/[^/]+/' || true; } | cut -d/ -f1-2 | sort -u | while read -r d; do
    if [ -f "$d/pom.xml" ]; then echo "$d"; fi
  done
}

changed_specs() { changed_files | grep -E '^services/[^/]+/src/main/resources/openapi\.yaml$' || true; }
changed_migration_dirs() { changed_files | grep -E "$MIGRATION_RE" | xargs -r -n1 dirname | sort -u || true; }

# 文件是否已提交（存在于 HEAD 或 BASE）：已提交的迁移可能已在某个环境执行过，必须不可变
is_committed() { git cat-file -e "HEAD:$1" 2>/dev/null || { [ -n "${BASE:-}" ] && git cat-file -e "$BASE:$1" 2>/dev/null; }; }

# ---- 度量：每次拦截 / 检查都追加一行 JSON 到 .harness/metrics.jsonl（已 gitignore），make report 汇总 ----
METRICS_FILE="${HARNESS_METRICS_FILE:-$ROOT/.harness/metrics.jsonl}"
now_ms() { python3 -c 'import time;print(int(time.time()*1000))' 2>/dev/null || echo $(( $(date +%s) * 1000 )); }
json_str() { local s="${1//\\/\\\\}"; s="${s//\"/\\\"}"; s="${s//$'\n'/ }"; s="${s//$'\t'/ }"; printf '"%s"' "$s"; }
# metric <event> key=value ...
metric() {
  [ "${HARNESS_METRICS:-1}" = "0" ] && return 0
  local ev="$1"; shift
  local line="{\"ts\":$(now_ms),\"event\":$(json_str "$ev"),\"source\":$(json_str "${HARNESS_SOURCE:-cli}"),\"session\":$(json_str "${HARNESS_SESSION:-${HARNESS_SOURCE:-cli}-$(date +%Y%m%d)}")"
  local kv
  for kv in "$@"; do line+=",$(json_str "${kv%%=*}"):$(json_str "${kv#*=}")"; done
  mkdir -p "$(dirname "$METRICS_FILE")" 2>/dev/null && printf '%s}\n' "$line" >> "$METRICS_FILE" 2>/dev/null || true
}

c_red()   { printf '\033[31m%s\033[0m\n' "$*"; }
c_green() { printf '\033[32m%s\033[0m\n' "$*"; }
