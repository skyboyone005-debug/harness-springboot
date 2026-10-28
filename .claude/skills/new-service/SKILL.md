---
name: new-service
description: 需要新增一个微服务时使用。生成可运行且通过契约测试的 Spring Boot 模块，并自动接入根 pom、网关、dev/mock、SDK。
---

# 新服务

1. 确认边界：一句话写清"这个服务拥有哪些数据"。如果只是给现有服务加接口，改用 `api-change`。
2. `make new-service NAME=<name>`（小写字母数字，同时是 Java 包名 `com.harness.<name>`）。
   生成 `services/<name>/`（pom、Application、示例 Controller、openapi.yaml、契约测试）并注册到根 `pom.xml`，已能通过测试。
3. 把模板里的 `items` 示例替换为真实资源 —— 按 `api-change` 流程走（先契约后实现）。
4. 依赖其他服务：在本服务内定义接口（如 `UserClient`）+ `RestClient` 实现，URL 来自 `${<SVC>_SVC_URL}`（`make dev` 自动注入）；
   连接失败映射为 `ApiException(BAD_GATEWAY, "upstream_unavailable")`；测试用 `@MockitoBean` 替换。参考 `services/order`。
5. 自动接入，无需手改：端口与网关路由（`scripts/dev/services.sh` + `GATEWAY_UPSTREAMS_<NAME>`）、`make mock`、`make sdk`。
6. 在 `docker-compose.yml` 增加该服务及网关环境变量，`make verify` 通过。
