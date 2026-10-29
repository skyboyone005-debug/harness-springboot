package com.harness.user;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.harness.common.ApiException;

/**
 * 基于 Spring 6 JdbcClient 的存储实现。表结构由 Flyway 迁移 db/migration/V*__*.sql 管理。
 * 对外 id 形如 "u_42"：数据库自增主键 + 前缀，既保持契约稳定，又不暴露裸数字。
 */
@Repository
class JdbcUserStore implements UserStore {

    private static final String PREFIX = "u_";

    private final JdbcClient jdbc;

    JdbcUserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public User create(String name, String email) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS); // 与 TIMESTAMP(6) 精度一致，读写一致
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        try {
            jdbc.sql("INSERT INTO users (name, email, created_at) VALUES (:name, :email, :createdAt)")
                    .param("name", name)
                    .param("email", email)
                    .param("createdAt", Timestamp.from(now))
                    .update(key, "id");
        } catch (DuplicateKeyException e) { // 唯一约束兜底并发重复注册，比"先查后插"可靠
            throw new ApiException(HttpStatus.CONFLICT, "email_taken", "email " + email + " already registered");
        }
        return new User(PREFIX + key.getKeyAs(Number.class).longValue(), name, email, now);
    }

    @Override
    public Optional<User> find(String id) {
        Long dbId = parse(id);
        if (dbId == null) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT id, name, email, created_at FROM users WHERE id = :id")
                .param("id", dbId)
                .query(JdbcUserStore::map)
                .optional();
    }

    @Override
    public List<User> list(int limit) {
        return jdbc.sql("SELECT id, name, email, created_at FROM users ORDER BY id LIMIT :limit")
                .param("limit", limit)
                .query(JdbcUserStore::map)
                .list();
    }

    private static User map(ResultSet rs, int row) throws SQLException {
        return new User(PREFIX + rs.getLong("id"), rs.getString("name"), rs.getString("email"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** "u_42" -> 42；格式不对直接视为不存在（404），不抛 500。 */
    private static Long parse(String id) {
        if (id == null || !id.startsWith(PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(id.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
