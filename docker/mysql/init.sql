-- 仅用于本地 docker compose：每个服务独立库（库内表结构由各服务的 Flyway 迁移创建）
CREATE DATABASE IF NOT EXISTS user_db;
CREATE DATABASE IF NOT EXISTS order_db;
