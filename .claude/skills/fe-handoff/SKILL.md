---
name: fe-handoff
description: 契约发生变化后，给前端生成交接说明（变更清单、示例、mock 与 SDK 用法）。在 api-change 完成后或用户要求"通知前端"时使用。
---

# 前端交接

1. 运行 `make contract-diff` 与 `make sdk`，收集输出。
2. 写 `docs/handoff/<YYYY-MM-DD>-<topic>.md`，严格使用以下结构（前端扫一眼就能开工）：

```markdown
# <topic>
- 服务 / 契约版本：<svc> <info.version>
- 是否破坏性：否 | 是（迁移步骤见下）
## 变更
<粘贴 contract-diff 输出，按 operation 分组；每条补一句业务含义>
## 调用示例
<TS，基于 web-sdk：const api = createApi(); await api.<svc>.POST("/v1/...", { body })>
## 错误码
| code | HTTP | 前端处理建议 |
## 联调
- 现在就能联调（mock）：`make mock` → http://localhost:8080/api/<svc>
- 真实后端：`make dev`；状态：已合并 | 开发中
```

3. 示例必须来自 `openapi.yaml` 的 `example`，不要编造字段。
4. 在回复里给出该文件路径，以及一句话摘要（可直接贴到群里）。
