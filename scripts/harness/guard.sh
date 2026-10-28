#!/usr/bin/env bash
# PreToolUse 守卫：拦截危险命令与对受保护文件的写入。exit 2 = 阻止并把原因反馈给 Agent。
# 人类维护 harness 时：HARNESS_UNLOCK=1 claude
source "$(dirname "$0")/lib.sh"
read_hook_input

deny() { echo "BLOCKED by harness guard: $1" >&2; exit 2; }

tool="$(json_get '.tool_name')"
case "$tool" in
  Bash)
    cmd="$(json_get '.tool_input.command')"
    shopt -s nocasematch
    [[ "$cmd" =~ rm[[:space:]]+-[a-z]*r[a-z]*f?[[:space:]]+(/|~|\$HOME|\.)([[:space:]]|$) ]] && deny "recursive delete of root/home/repo"
    [[ "$cmd" =~ git[[:space:]]+push.*(--force|[[:space:]]-f([[:space:]]|$)) ]] && deny "force push; open a PR instead"
    [[ "$cmd" =~ git[[:space:]]+reset[[:space:]]+--hard ]] && deny "git reset --hard discards work; use git stash"
    [[ "$cmd" =~ --no-verify ]] && deny "--no-verify skips quality gates; fix the failure instead"
    [[ "$cmd" =~ (curl|wget).*\|[[:space:]]*(ba)?sh ]] && deny "piping remote scripts into a shell"
    [[ "$cmd" =~ drop[[:space:]]+(table|database) ]] && deny "destructive SQL; write a Flyway/Liquibase migration"
    ;;
  Edit|Write|MultiEdit|NotebookEdit)
    f="$(relpath "$(json_get '.tool_input.file_path')")"
    case "$f" in
      web-sdk/src/gen/*)  deny "$f is generated. Edit services/<svc>/src/main/resources/openapi.yaml then run: make sdk" ;;
      */target/*)         deny "$f is a build output" ;;
      .env|.env.*|*/application-prod.yml|*/application-prod.yaml) deny "secrets / prod config are off-limits" ;;
      .claude/settings.json|scripts/harness/*|.githooks/*)
        [ "${HARNESS_UNLOCK:-}" = "1" ] || deny "$f is part of the harness; agents may not loosen their own guardrails (human: HARNESS_UNLOCK=1)" ;;
    esac
    ;;
esac
exit 0
