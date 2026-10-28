// 前端用法示例：类型全部来自契约，字段写错/漏传会在编译期报错。
import { createApi, type UserSchemas } from "./src/gen";

const api = createApi(); // 默认 http://localhost:8080 (gateway)

export async function demo(): Promise<UserSchemas["User"] | undefined> {
  const { data, error } = await api.user.POST("/v1/users", {
    body: { name: "Alice", email: "alice@example.com" },
  });
  if (error) {
    console.error(error.code, error.message); // 统一错误体，按 code 分支
    return;
  }
  const orders = await api.order.GET("/v1/orders", { params: { query: { userId: data.id } } });
  console.log(orders.data?.items.length);
  return data;
}
