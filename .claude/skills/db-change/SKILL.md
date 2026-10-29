---
name: db-change
description: 需要新增或修改表结构、索引、数据修复时使用。按"只追加迁移 + 向后兼容"流程写 Flyway 迁移，并同步存储层代码与测试。
---

# 数据库变更（只追加、向后兼容）

1. **永远新增文件**：`services/<svc>/src/main/resources/db/migration/V<当前最大+1>__<snake_case 描述>.sql`。
   已提交的迁移不可修改（PreToolUse 守卫会直接拦截）——它可能已在测试/生产执行过，改了 Flyway 会因 checksum 不一致拒绝启动。
2. **写兼容的 SQL**（保存后 PostToolUse 自动跑 migration-lint）：
   - 加列：可空，或 `NOT NULL DEFAULT ...`；不要 `NOT NULL` 无默认值
   - 改名 / 改类型 / 删列：拆成"先加新列 → 代码双写 → 回填 → 下个版本再删旧列"，每步一个迁移
   - 数据修复：`UPDATE/DELETE` 必须带 `WHERE`
   - 确需破坏性操作：语句前一行写 `-- harness:allow-destructive <原因>`，原因要能让审查的人判断（例如"该列自 2026-08 起无读写，已与 BI 确认"）
   - SQL 同时兼容 H2(MySQL 模式) 与 MySQL：避免 MySQL 专有语法（如 `ENGINE=`、内联 `INDEX`），索引用单独的 `CREATE INDEX`
3. **改存储层**：`Jdbc*Store` 里的 SQL 与 record 字段；多表写入放在同一个 `@Transactional` 方法里。
4. **测试**：契约测试会在全新 H2 上跑全部迁移；涉及事务/回滚的逻辑参考 `services/order/.../JdbcOrderStoreTest`。
5. **验证**：`make migration-check` → `make verify`。字段要暴露给前端时，接着走 `api-change`。
