---
name: api-change
description: 新增或修改任何 HTTP 接口（字段、路由、错误码）时使用。按契约优先流程：openapi.yaml → Controller/DTO → 契约测试 → SDK → 交接。
---

# API 变更（契约优先）

1. **定契约**：编辑 `services/<svc>/src/main/resources/openapi.yaml`
   - 每个 operation 必须有 `operationId`（= 前端 SDK 方法名）、`summary`、`tags`
   - 请求体 `additionalProperties: false` + `example`；2xx 响应带 `example`（mock 用）
   - 4xx/5xx 一律 `$ref: '#/components/responses/Error'`
   - 保存后 PostToolUse hook 会跑 speclint，报错就地修
2. **看影响**：`make contract-diff`。出现 `[BREAKING]` 时优先改成兼容方案：
   - 新字段设为可选 / 给默认值；改类型 → 新增字段并废弃旧字段；删接口 → 先标 `deprecated: true`
   - 确需破坏：提升 `info.version` 主版本，并在交接说明中写迁移步骤
3. **实现**
   - DTO 用 `record`，字段名与契约一致；约束用 Bean Validation 对齐契约（`@NotBlank` `@Size` `@Email` `@Min`）
   - Controller 方法签名对应 operation；业务错误 `throw new ApiException(HttpStatus.X, "code", msg)`
   - 查询参数约束直接写在参数上（`@Min(1) int limit`），Spring 6 会自动校验并由 `GlobalExceptionHandler` 转成 400
4. **契约测试**：在 `*ContractTest` 用 `contract.on(mvc).post(...).expect(201).as(X.class)` 覆盖新 operation 的成功路径 + 每个声明的错误码。
   `Contract` 会自动：拒绝未声明的路由、按 schema 校验响应、测试类结束时检查所有 operation 都被调用过。
5. **验证**：`mvn -q -pl services/<svc> -am test` → `make verify`
6. **前端**：`make sdk`，然后调用 `fe-handoff` skill。

反模式：先写代码后补契约；在测试里直接用 `mvc.perform` 绕过 `Contract`；为过测试修改契约去迎合错误实现；用 `@JsonIgnoreProperties(ignoreUnknown = true)` 绕开严格 JSON。
