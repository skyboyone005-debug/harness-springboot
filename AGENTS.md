# AGENTS.md

Spring Boot 3 微服务（Maven 多模块，Java 17）。**契约优先**：`services/<svc>/src/main/resources/openapi.yaml` 是唯一真相，实现、测试、前端 SDK、mock 都从它派生。
本文件对 Claude Code 与 Codex 通用；只写"地图 + 不可违反的规则"，流程细节在 `.claude/skills/`（Codex 经 `.agents/skills` 读取同一份）。

## 地图
```
services/<svc>/src/main/resources/openapi.yaml   契约（先改这里）
services/<svc>/src/main/java/.../*Controller      路由须与契约一一对应
services/<svc>/src/test/java/.../*ContractTest    契约测试（必须经 com.harness.contract.Contract）
services/<svc>/src/main/resources/db/migration     Flyway 迁移 V<n>__<desc>.sql（已提交的不可改）
services/gateway        前端唯一入口 :8080，/api/<svc>/** -> 服务
common-web              统一错误体 / 全局异常 / request-id / 严格 JSON（自动配置，服务内禁止另起一套）
harness-contract        契约测试扩展 + speclint + specdiff + migration-lint + report（纯 Java）
web-sdk/src/gen         生成的前端 SDK（禁止手改）
```

## 命令
| 目的 | 命令 |
|---|---|
| 增量质量门（结束前必须通过） | `make verify` |
| 全量（CI 同款） | `make ci` |
| 单服务测试 | `mvn -q -pl services/<svc> -am test` |
| 契约变更对前端的影响 | `make contract-diff` |
| 重新生成前端 SDK | `make sdk` |
| 本地全栈 / 纯 mock | `make dev` / `make mock` |
| 新服务骨架 | `make new-service NAME=<name>` |
| 数据库迁移检查 | `make migration-check` |
| harness 质量报告 | `make report` |

## 规则（hooks/CI 会机械执行，违反会被直接打回）
1. 改 API = 先改 `openapi.yaml`，再改 Controller/DTO，再补契约测试。禁止实现未声明的路由。
2. 错误一律抛 `ApiException(status, code, msg)`；`code` 是前端分支依据，**改名即破坏性变更**。不要在 Controller 里手写错误响应。
3. 破坏性变更（删字段、改类型、新增必填）默认被拒；确需破坏时提升 `info.version` 主版本并写交接说明。
4. Controller 测试只能通过 `Contract.on(mvc)` 发请求；不得删测试、`@Disabled`、放宽断言来"通过"。
5. 服务间只依赖对方契约（接口 + `RestClient` 实现），不得依赖其他服务的 Maven 模块。
6. DTO 用 `record` + Bean Validation，约束与契约一致（`minLength`→`@Size`，`required`→`@NotNull/@NotBlank`）。
7. 不改 `web-sdk/src/gen/`、`target/`、harness 文件（`.claude/settings.json`、`scripts/harness/`）。
8. 表结构只通过新增 Flyway 迁移变更：已提交的迁移不可修改；破坏性语句（DROP/RENAME/MODIFY/无 WHERE 的 DELETE）走"先加后删"两步走，确需执行须写 `-- harness:allow-destructive <原因>`。

## 完成定义（DoD）
- `make verify` 通过（Claude Code 的 Stop hook 会自动跑；Codex 请手动跑并贴结果）。
- 契约有变化时：`make sdk` 已执行，并用 `fe-handoff` skill 产出 `docs/handoff/<date>-<topic>.md`。
- 回复中列出：改了哪些契约 operation、是否破坏性、如何验证。
