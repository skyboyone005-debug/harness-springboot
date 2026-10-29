#!/usr/bin/env bash
# PreToolUse 守卫：拦截危险命令与对受保护文件的写入。exit 2 = 阻止并把原因反馈给 Agent。
# 人类维护 harness 时：HARNESS_UNLOCK=1 claude
source "$(dirname "$0")/lib.sh"
read_hook_input

deny() { metric guard.block "rule=$1" "tool=$tool"; echo "BLOCKED by harness guard: $2" >&2; exit 2; }

tool="$(json_get '.tool_name')"
case "$tool" in
  Bash)
    cmd="$(json_get '.tool_input.command')"
    shopt -s nocasematch
    [[ "$cmd" =~ rm[[:space:]]+-[a-z]*r[a-z]*f?[[:space:]]+(/|~|\$HOME|\.)([[:space:]]|$) ]] && deny rm-rf "recursive delete of root/home/repo"
    [[ "$cmd" =~ git[[:space:]]+push.*(--force|[[:space:]]-f([[:space:]]|$)) ]] && deny force-push "force push; open a PR instead"
    [[ "$cmd" =~ git[[:space:]]+reset[[:space:]]+--hard ]] && deny reset-hard "git reset --hard discards work; use git stash"
    [[ "$cmd" =~ --no-verify ]] && deny no-verify "--no-verify skips quality gates; fix the failure instead"
    [[ "$cmd" =~ (curl|wget).*\|[[:space:]]*(ba)?sh ]] && deny curl-sh "piping remote scripts into a shell"
    [[ "$cmd" =~ (drop[[:space:]]+(table|database)|truncate[[:space:]]+table) ]] && deny destructive-sql "destructive SQL from the shell; write a reviewed Flyway migration instead"
    [[ "$cmd" =~ flyway.*(clean|repair) ]] && deny flyway-clean "flyway clean/repair rewrites schema history; ask a human"
    ;;
  Edit|Write|MultiEdit|NotebookEdit)
    f="$(relpath "$(json_get '.tool_input.file_path')")"
    case "$f" in
      web-sdk/src/gen/*)  deny generated-file "$f is generated. Edit services/<svc>/src/main/resources/openapi.yaml then run: make sdk" ;;
      */target/*)         deny build-output "$f is a build output" ;;
      .env|.env.*|*/application-prod.yml|*/application-prod.yaml) deny secrets "secrets / prod config are off-limits" ;;
      .claude/settings.json|scripts/harness/*|.githooks/*)
        [ "${HARNESS_UNLOCK:-}" = "1" ] || deny harness-self "$f is part of the harness; agents may not loosen their own guardrails (human: HARNESS_UNLOCK=1)" ;;
      services/*/src/main/resources/db/migration/*.sql)
        if is_committed "$f"; then
          deny migration-immutable "$f is a committed migration and may already have run somewhere; never edit it — add a new V<next>__<desc>.sql in the same folder"
        fi ;;
    esac
    ;;
esac
exit 0
