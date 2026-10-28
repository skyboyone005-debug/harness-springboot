@AGENTS.md

## Claude Code 专属
- Hooks 已启用（`.claude/settings.json`）：编辑 `.java` 后自动编译所在模块、编辑契约后自动 speclint；结束前自动 `verify`。hook 报错时**修复根因**，不要绕过。
- Maven 慢：调试单个测试用 `mvn -q -pl services/<svc> -am test -Dtest=<Class>#<method> -Dsurefire.failIfNoSpecifiedTests=false`，不要反复跑全量。
- 复杂改动先用 Plan 模式；完成后可让 `reviewer` 子代理做一次独立审查。
- Skills：`api-change`（改/加接口）、`new-service`（新服务）、`fe-handoff`（前端交接）。
