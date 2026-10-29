# harness-springboot

一个面向 **Claude Code / Codex** 的 Spring Boot 微服务 Harness Engineering 样板：
Spring Boot 3.5（Java 17，Maven 多模块）user / order / gateway 三个服务 + 契约优先 + 精简的 AGENTS.md / skills / hooks / subagent。

**高级版新增**：JdbcClient + Flyway 持久化与**数据库迁移守卫**（已提交迁移不可改、破坏性 SQL 拦截），以及**质量度量看板**（用数据回答"这套 harness 到底有没有用"）。见 [1.8](#18-数据库迁移守卫高级版) / [1.9](#19-质量度量用数据证明-harness-有效高级版)。

> Harness Engineering 的核心观点：**Agent 的产出质量 ≈ 它所处环境的反馈质量**。
> 与其在提示词里写"请注意 xxx"，不如把规则变成**可执行的检查**，在 Agent 犯错的那一刻就把错误信息喂回去。

---

## 1. 设计思路

### 1.1 三个原则

| 原则 | 落地 |
|---|---|
| **规则写成代码，不写成散文** | API 规范 → `SpecLint`；实现与契约一致 → `Contract`（JUnit 5 扩展）；兼容性 → `SpecDiff`；危险操作 → `guard.sh`。AGENTS.md 只保留"地图 + 不可违反的规则"。 |
| **反馈越快越好，越近越好** | 四层反馈：执行前拦截（PreToolUse）→ 编辑后秒级（PostToolUse）→ 结束前增量（Stop）→ 提交 / CI 全量。每层只做该层该做的事。 |
| **单一事实来源，向两端派生** | `openapi.yaml` 派生出：后端契约测试、前端 TS SDK、Prism mock、变更日志、交接文档。前后端只围绕契约协作。 |

### 1.2 架构

```mermaid
flowchart LR
  C["services/&lt;svc&gt;/src/main/resources/openapi.yaml<br/>(唯一真相)"]
  C -->|Contract 扩展校验| Impl[Controller + 契约测试]
  C -->|make sdk| SDK[web-sdk 类型安全客户端]
  C -->|make mock| Mock[Prism mock]
  C -->|SpecDiff| Log[变更日志 / 破坏性检测]
  Log -->|fe-handoff skill| Doc[docs/handoff/*.md]
  FE[前端] --> GW[gateway :8080 /api/&lt;svc&gt;/**]
  GW -->|make dev| Impl
  GW -->|make mock| Mock
  SDK --> FE
```

模块划分：

| 模块 | 作用 |
|---|---|
| `harness-contract` | 契约测试扩展 `Contract`、`SpecLint`、`SpecDiff`。只依赖 SnakeYAML + Jackson，额外打出 `*-cli.jar`，启动 <0.5s |
| `common-web` | Spring Boot **自动配置**：统一错误体、`@RestControllerAdvice`、request-id 过滤器、严格 JSON。服务引入依赖即生效，零代码 |
| `services/user` | 用户服务：record DTO + Bean Validation；`JdbcClient` 存储，唯一约束兜底并发重复注册（409）；Flyway 迁移 |
| `services/order` | 订单服务：订单头 + 明细两表同一 `@Transactional` 写入（有回滚测试）；经 `RestClient` 调 user-service，依赖边界是 `UserClient` 接口 |
| `services/gateway` | 前端唯一入口 `/api/{svc}/**`，剥离前缀反向代理 + CORS + request-id 贯穿 |

### 1.3 反馈分层（快速高质量迭代的关键）

| 层 | 触发 | 做什么 | 耗时（本机实测） | 失败时 |
|---|---|---|---|---|
| L0 守卫 | `PreToolUse` | 拦截 `push -f` / `--no-verify` / `reset --hard` / shell 里的 `DROP TABLE`、写生成文件 / `target/`、**改已提交的迁移**、改 harness 自身 | ms | 阻止并说明原因 |
| L1 即时 | `PostToolUse` | `.java` → `mvn -pl <模块> -am compile`；`openapi.yaml` → speclint + 注入"下一步"提示；`db/migration/*.sql` → migration-lint | 编译约 3–4s，lint <0.5s | 错误回灌，当轮修复 |
| L2 增量 | `Stop` | 只对**受影响模块**：speclint → contract-diff → `mvn -pl … -am test` | 单服务约 10s | 阻止结束，回灌**摘要后**的错误 |
| L3 全量 | git pre-commit / CI | `verify --all` + PR 对 base 的破坏性检测 + SDK 是否已重新生成 | 约 30s | 拒绝合并 |

**Java 特有的取舍**（面试常问）：
- Maven 比 Go 慢一个量级，所以 L1 **只编译不测试**（编辑几十次，每次跑测试不可接受），测试放到 L2；L2 用 `-pl … -am` 只构建受影响模块及其依赖。
- 改了 `common-web` / `harness-contract` / 根 `pom.xml` 才全量，因为共享模块会影响所有服务。
- `SpecLint`/`SpecDiff` 不走 Maven 插件，而是打成独立 fat jar（`spring-boot-maven-plugin` 的 `classifier=cli`），保证契约检查是秒级的。

其他细节：
- **防死循环**：Stop hook 读取 `stop_hook_active`，同一轮只拦一次，第二次放行交给人类。
- **错误摘要**：Maven 输出几百行，失败时只抽取 `<<< FAILURE`、`violates contract`、`.java:[行,列]`、`BREAKING` 等关键行，再给 8 行尾部上下文。
- **防自我放水**：Agent 不能修改 `.claude/settings.json`、`scripts/harness/`、`.githooks/`（人类用 `HARNESS_UNLOCK=1 claude` 解锁）。
- **不制造噪音**：测试已有失败时，契约覆盖率检查自动跳过，避免掩盖真正的错误。

### 1.4 契约测试（`harness-contract` 的 `Contract`）

```java
@SpringBootTest
@AutoConfigureMockMvc
class UserContractTest {
    @RegisterExtension
    static Contract contract = Contract.forSpec("src/main/resources/openapi.yaml");
    @Autowired MockMvc mvc;

    @Test
    void createUser() {
        User u = contract.on(mvc).post("/v1/users", Map.of("name", "Alice", "email", "a@x.io"))
                .expect(201).as(User.class);
    }
}
```

它机械保证三件事：
1. **路由必须在契约中声明**：实现里不可能悄悄多出一个接口；
2. **每个响应都按契约校验**（状态码是否已声明、字段、类型、必填、枚举、格式），实现与契约不会漂移；
3. **测试类结束时，契约里每个 operation 都至少被调用过一次**，不会出现"写了契约没测"。

### 1.5 为什么选择"运行时契约测试"而不是 openapi-generator 生成接口

两条路都是契约优先。这里选运行时校验，理由是：
- 生成代码会让 Agent 面对大量生成物（还要防它手改），上下文噪音大；
- 生成接口只保证**签名**一致，管不到**行为**（返回了契约没声明的状态码、漏了必填字段），而契约测试两者都能抓到；
- 零额外 Maven 插件，构建更快、更稳定。

如果团队已经在用 openapi-generator，也可以叠加使用：生成 `interfaceOnly` 的 `XxxApi` 让 Controller 去实现，`Contract` 继续负责行为校验。

### 1.6 前后端快速协作

| 前端痛点 | 方案 |
|---|---|
| 后端还没写完，前端动不了 | `make mock`：Prism 按契约里的 `example` 返回数据，地址就是同一个 gateway，后面切真实后端前端零改动 |
| 字段名、类型对不上 | `make sdk`：openapi-typescript + openapi-fetch，路径、参数、body、响应都在编译期做类型检查 |
| 后端悄悄改接口 | `SpecDiff`：删字段、改类型、新增必填都算 BREAKING，默认拒绝；只有提升主版本才放行 |
| 不知道这次改了什么 | `fe-handoff` skill：基于 contract-diff 生成固定格式的交接文档 |
| 错误处理五花八门 | 全局统一返回 `{code, message, requestId}`；JSON 解析错误不泄露类名；`code` 改名算破坏性变更 |
| 跨域、多端口 | gateway 统一入口 `/api/<svc>`，内置 CORS，透传 `X-Request-Id`，前后端可以按同一个 ID 对日志 |

### 1.7 为什么"精简"

| 组件 | 数量 | 取舍 |
|---|---|---|
| AGENTS.md | 1 份（约 50 行） | 只放地图、命令、7 条规则、DoD。Claude 通过 `CLAUDE.md` 的 `@AGENTS.md` 复用，Codex 原生读取，**只维护一份** |
| Skills | 3 个 | `api-change` / `new-service` / `fe-handoff`，按需加载，不常驻上下文 |
| Hooks | 3 个事件 | Pre（守卫）/ Post（即时）/ Stop（增量门）。逻辑全在 `scripts/harness/*.sh`，hook 只是调用方 |
| Subagent | 1 个 | `reviewer`：只读，独立上下文，带 Java/Spring 检查清单，避免"自己审自己" |

**跨工具复用**：Claude Code 通过 hooks 调用这些脚本；Codex 和人类通过 `make verify` 与 git pre-commit 调用；CI 调用 `verify --all`。`.agents/skills` 是指向 `.claude/skills` 的软链。

高级版多了一个 `db-change` skill（共 4 个）：数据库变更的流程和接口变更差别很大，放进 `api-change` 会让两个流程都变长。

### 1.8 数据库迁移守卫（高级版）

AI 改表结构最危险：代码改错了回滚就行，**数据删了回不来**，已执行过的迁移被改了 Flyway 直接拒绝启动。所以这部分规则最严，并且全部机械化：

| 规则 | 在哪一层拦 | 为什么 |
|---|---|---|
| 已提交的迁移不可修改 / 删除 | L0（编辑前就拦）+ L2/L3（git diff 兜底） | 它可能已在测试 / 生产执行过；改了 checksum 不一致，Flyway 拒绝启动 |
| 文件名 `V<n>__<snake_case>.sql`，版本不重复 | L1 migration-lint | 命名错了 Flyway 会静默跳过 |
| 新迁移版本号必须大于已有最大版本 | L1 / L2 | 插队的版本在已上线环境不会执行，环境之间表结构悄悄分叉 |
| 破坏性语句默认拒绝：DROP TABLE/COLUMN、TRUNCATE、RENAME、MODIFY/CHANGE、无 WHERE 的 DELETE/UPDATE | L1 / L2 | 旧版本应用在滚动发布期间还在读这些列；强制"先加后删"两步走 |
| 确需破坏：语句前写 `-- harness:allow-destructive <原因>`，原因不能为空 | L1 | 不是不让做，而是让人审查时有据可查 |
| `ADD COLUMN ... NOT NULL` 必须带 DEFAULT | L1 | 有数据的表上会直接失败 |
| shell 里直接 `DROP TABLE` / `flyway clean` | L0 | 绕过迁移的变更无法审查、无法复现 |

存储层用 Spring 6 的 `JdbcClient`（不用 JPA）：SQL 显式可见，AI 改出来的 SQL 人一眼能审，也方便和迁移文件对照。
默认数据源是内存 H2（MySQL 兼容模式），clone 下来零依赖就能跑测试和 `make dev`；`SPRING_PROFILES_ACTIVE=mysql` 或 `make up` 连真实 MySQL 8.4。

### 1.9 质量度量：用数据证明 harness 有效（高级版）

"这套东西真的有用吗？"是面试和落地时最常被问的问题。每个 hook 在拦截或放行时都往 `.harness/metrics.jsonl` 追加一行事件（会话 id 来自 Claude Code 的 hook 输入），`make report` 汇总成终端报告 + HTML 看板：

| 指标 | 口径 | 怎么解读 |
|---|---|---|
| Stop 一次通过率 | Agent 会话中第一次想结束时就通过 verify 的比例 | 越高说明前面几层把问题挡得越早，Agent 不用反复返工 |
| 平均修复轮数 | 首次通过前被 Stop 拦下的次数 | 持续偏高 → 规则 / 文档不清楚，该补 skill 或 AGENTS.md |
| 早层拦截占比 | L0 + L1 拦下的问题 / 全部拦截 | 越高越便宜：编辑时 3 秒发现，好过提交时 30 秒、上线后几天 |
| 升级到人工 | Stop 连续拦截后放行给人的次数 | 每一次都值得复盘：是 Agent 能力边界，还是规则本身有问题 |
| 拦截原因 Top / 检查耗时 | 按规则 / 步骤分组 | 找出最常犯的错，和拖慢反馈的那一步 |

[`docs/demo/report.html`](docs/demo/report.html) 是一份示例看板：数据由**真实 hook 调用**生成（6 个模拟会话：加字段、改名、改契约、重构、修不好、危险命令），不是手写的数字。
度量文件默认不提交（`.gitignore`）；团队落地时可以在 CI 收集各人的 `metrics.jsonl` 做周报。

---

## 2. 如何使用

### 2.1 准备
```bash
# 依赖：JDK 17+、Maven 3.9+、Node 18+（仅 sdk/mock 需要）、jq（可选）
make setup        # 启用 git hooks + mvn install（安装 harness-contract / common-web）
make help
```
IntelliJ IDEA：直接 Open 根目录的 `pom.xml`，作为 Maven 项目导入即可。

### 2.2 Claude Code
```bash
claude            # 自动加载 CLAUDE.md(→AGENTS.md)、.claude/settings.json(hooks)、skills、agents
```
典型对话：
```
> 给订单增加可选字段 currency（ISO 4217），前端下周要用
  # 选用 api-change skill：改 openapi.yaml → speclint 即时检查 → 改 record DTO + Controller
  # → 补契约测试 → Stop hook 跑增量 verify → make sdk → fe-handoff 产出交接文档
> 用 reviewer 子代理审一下这次改动
> 给用户加手机号字段
  # db-change skill：新增 V2__add_user_phone.sql（可空或带默认值）→ migration-lint 即时检查
  # → 改 JdbcUserStore → 需要暴露给前端时接着走 api-change
> 新建一个 payment 服务，负责支付单
  # new-service skill → make new-service NAME=payment → 模块已注册到根 pom 且测试通过
```

### 2.3 Codex
```bash
make hooks        # 一次性：git pre-commit 走同一套 verify
codex             # 读取 AGENTS.md；DoD 要求结束前运行 make verify
```

### 2.4 人类 / 前端
```bash
make dev          # 真实服务：gateway :8080，order :8081，user :8082
make mock         # 纯 mock（Prism），前端立即可联调
make sdk          # 生成 web-sdk/src/gen（前端 import "@harness/web-sdk"）
make contract-diff
make migration-check   # 数据库迁移检查
make report       # 质量报告：终端 markdown + .harness/report.html
make up           # docker compose（含 MySQL 8.4）
MVN="mvn -o" make verify   # 离线加速
```

---

## 3. 演示（均已实测）

```text
# ① 实现漂移：把 record User 的 email 改名为 mail，Agent 尝试结束
verify FAILED at step [mvn test (services/user)]:
  UserContractTest.createGetAndList:33 response violates contract for POST /v1/users (status 201):
  - $: required property 'email' is missing

# ② 契约不规范：删掉 getOrder 的 operationId（PostToolUse 当场反馈，<0.5s）
services/order/src/main/resources/openapi.yaml: GET /v1/orders/{id}: missing operationId (前端 SDK 方法名来源)

# ③ 编译错误（PostToolUse，约 3.6s）
compile failed after editing .../UserController.java:
[ERROR] .../UserController.java:[47,21] cannot find symbol
[ERROR]   symbol:   method findd(java.lang.String)

# ④ 破坏性变更：createOrder 新增必填字段 currency
verify FAILED at step [contract-diff]:
  [BREAKING]  POST /v1/orders  request: new required field currency
# 改为可选                → [added/changed] request: new optional field currency      ✓
# 或 info.version → 2.0.0 → 1 breaking change(s) allowed: major version bumped        ✓

# ⑤ 未声明的路由 / 漏测的 operation
DELETE /v1/users/u_1 is not declared in the contract: method not declared for this path
contract coverage: operations never exercised by tests: [createUser (POST /v1/users), getUser (GET /v1/users/{id})]

# ⑥ 守卫
BLOCKED by harness guard: --no-verify skips quality gates; fix the failure instead
BLOCKED by harness guard: scripts/harness/verify.sh is part of the harness; agents may not loosen their own guardrails

# ⑦ 改已提交的迁移（PreToolUse 编辑前就拦）
BLOCKED by harness guard: .../db/migration/V1__create_users.sql is a committed migration and may already
have run somewhere; never edit it — add a new V<next>__<desc>.sql in the same folder

# ⑧ 新迁移里删列 + NOT NULL 无默认值（PostToolUse，约 0.3s）
V2__drop_name.sql:1: destructive statement (drops a column; old app versions still read it): ALTER TABLE USERS DROP COLUMN NAME
  -> make it backward compatible, or precede it with '-- harness:allow-destructive <reason>'
V2__drop_name.sql:2: ADD COLUMN ... NOT NULL without DEFAULT fails on tables that already have rows

# ⑨ 事务回滚（JdbcOrderStoreTest）：第 2 条明细写入失败 → 订单头一起回滚，库里 0 行
```

质量看板（`docs/demo/report.html`，由上面这些真实拦截生成）：

| 指标 | 值 |
|---|---|
| Stop 一次通过率 | 40% |
| 平均修复轮数 | 0.6 |
| 早层拦截占比（L0+L1） | 70% |
| 升级到人工 | 1 |

---

## 4. 目录

```
pom.xml                          # 父 POM（spring-boot-starter-parent 3.5.16）
AGENTS.md  CLAUDE.md
.claude/settings.json            # 权限白名单 + 3 个 hooks
.claude/skills/{api-change,db-change,new-service,fe-handoff}/SKILL.md
.claude/agents/reviewer.md
.agents/skills -> .claude/skills
scripts/harness/                 # guard / post-edit / verify / contract-diff / migration-check / contract-cli（harness 核心）
scripts/dev/                     # run(dev|mock) / sdk / new-service / services 注册表
harness-contract/                # Contract（JUnit5 扩展）、SpecLint、SpecDiff、MigrationLint、Report、OpenApi、SchemaValidator
common-web/                      # ApiException、GlobalExceptionHandler、RequestIdFilter、自动配置
services/{user,order,gateway}/   # user/order 含 src/main/resources/db/migration（Flyway）
templates/service/               # new-service 模板
web-sdk/                         # 前端 SDK（src/gen 为生成物）
docs/handoff/                    # 前端交接文档
docs/demo/                       # 示例度量数据 + 质量报告（report.md / report.html）
docker/mysql/init.sql            # docker compose 用：建 user_db / order_db
.githooks/pre-commit  .github/workflows/ci.yml  Dockerfile  docker-compose.yml
```

## 5. 扩展方向

- 迁移：加 Testcontainers 在 CI 里用真实 MySQL 跑一遍全部迁移；大表 DDL 接入 gh-ost / pt-osc 的检查。
- 度量：CI 汇总全队的 `metrics.jsonl`，按周看一次通过率趋势，用来决定该补哪条规则或 skill。
- 网关：生产环境换成 Spring Cloud Gateway（限流、熔断、鉴权），契约与 harness 不变。
- 质量门：在 L3 加 JaCoCo 覆盖率阈值、ArchUnit 分层规则（例如"服务之间禁止互相依赖"），把更多规则从文档搬到代码里。
- 可观测：request-id 已写入 MDC，可接 Micrometer Tracing / OpenTelemetry。
