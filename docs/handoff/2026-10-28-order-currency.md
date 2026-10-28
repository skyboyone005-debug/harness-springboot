# 订单支持币种（示例交接说明，由 fe-handoff skill 生成）
- 服务 / 契约版本：order-svc 1.1.0
- 是否破坏性：否

## 变更
```
## services/order/openapi.yaml  (vs 090dd8731e79)
  [added/changed] POST /v1/orders              request: new optional field currency
```
- `currency`：可选，ISO 4217 三位码，缺省按 `CNY` 处理。老页面无需改动。

## 调用示例
```ts
import { createApi } from "@harness/web-sdk";
const api = createApi();
const { data, error } = await api.order.POST("/v1/orders", {
  body: { userId: "u_1", currency: "USD", items: [{ sku: "SKU-1", qty: 2, priceCents: 1999 }] },
});
```

## 错误码
| code | HTTP | 前端处理建议 |
|---|---|---|
| bad_request | 400 | 表单校验提示 message |
| user_not_found | 422 | 引导重新登录 / 选择用户 |
| upstream_unavailable | 502 | 提示稍后重试 |

## 联调
- 现在就能联调（mock）：`make mock` → http://localhost:8080/api/order
- 真实后端：`make dev`；状态：开发中
