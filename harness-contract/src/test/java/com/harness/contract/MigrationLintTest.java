package com.harness.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MigrationLintTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "DROP TABLE users;",
            "ALTER TABLE users DROP COLUMN email;",
            "alter table users drop email;",
            "TRUNCATE TABLE orders;",
            "ALTER TABLE users RENAME COLUMN name TO full_name;",
            "RENAME TABLE users TO members;",
            "ALTER TABLE users MODIFY email VARCHAR(64);",
            "ALTER TABLE users CHANGE email mail VARCHAR(255);",
            "DELETE FROM users;",
            "UPDATE users SET name = 'x';"})
    void destructiveStatementsAreRejected(String sql) {
        assertThat(MigrationLint.lintSql("V2__x.sql", sql)).singleElement().asString().contains("destructive statement");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE t (id BIGINT);",
            "ALTER TABLE users ADD COLUMN phone VARCHAR(32);",
            "ALTER TABLE users ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'active';",
            "CREATE INDEX idx_users_name ON users (name);",
            "ALTER TABLE users DROP INDEX idx_users_name;",
            "UPDATE users SET status = 'active' WHERE status IS NULL;",
            "ALTER TABLE users ADD COLUMN change_reason VARCHAR(64);"})
    void safeStatementsPass(String sql) {
        assertThat(MigrationLint.lintSql("V2__x.sql", sql)).isEmpty();
    }

    @Test
    void allowAnnotationNeedsAReasonAndOnlyCoversTheNextStatement() {
        String sql = """
                -- harness:allow-destructive legacy column unused since 2026-08, confirmed with BI
                ALTER TABLE users DROP COLUMN legacy_flag;
                DROP TABLE tmp_import;
                -- harness:allow-destructive
                TRUNCATE TABLE audit_tmp;
                """;
        List<String> problems = MigrationLint.lintSql("V3__cleanup.sql", sql);
        assertThat(problems).hasSize(3);
        assertThat(problems.get(0)).startsWith("V3__cleanup.sql:3: destructive statement");
        assertThat(problems.get(1)).isEqualTo("V3__cleanup.sql:4: 'harness:allow-destructive' needs a reason after it");
        assertThat(problems.get(2)).startsWith("V3__cleanup.sql:5: destructive statement");
    }

    @Test
    void addNotNullColumnNeedsDefault() {
        assertThat(MigrationLint.lintSql("V2__x.sql", "ALTER TABLE users ADD COLUMN phone VARCHAR(32) NOT NULL;"))
                .singleElement().asString().contains("without DEFAULT");
    }

    @Test
    void namingDuplicatesAndOutOfOrderVersions(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("V1__create_users.sql"), "CREATE TABLE users (id BIGINT);");
        Files.writeString(dir.resolve("V3__add_phone.sql"), "ALTER TABLE users ADD COLUMN phone VARCHAR(32);");
        Files.writeString(dir.resolve("V2__late_branch.sql"), "CREATE INDEX i ON users (id);");
        Files.writeString(dir.resolve("v4-Bad Name.sql"), "SELECT 1;");
        List<String> problems = MigrationLint.lint(dir, Set.of("V2__late_branch.sql"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("V2__late_branch.sql: new migration V2 must be > V3"));
        assertThat(problems).anySatisfy(p -> assertThat(p).contains("v4-Bad Name.sql: file name must be"));
        assertThat(problems).hasSize(2);
    }
}
