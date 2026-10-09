package com.subjex.platform.app.connection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DeclarationMigrationSqlTextMigrationTest — V16 TEXT + V29 widen（MySQL 行大小修复，无 Docker）。
 */
class DeclarationMigrationSqlTextMigrationTest {

    @Test
    void flywayReachesV29OnH2PostgresqlMode() throws Exception {
        DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        assertSqlTextAcceptsLargePayload(source);
        assertFlywayAtLeast(source, 29);
    }

    @Test
    void flywayReachesV29OnH2MysqlMode() throws Exception {
        DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.MYSQL);
        assertSqlTextAcceptsLargePayload(source);
        assertFlywayAtLeast(source, 29);
    }

    private static void assertSqlTextAcceptsLargePayload(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        String big = "x".repeat(20_000);
        assertDoesNotThrow(() -> jdbc.update(
                """
                INSERT INTO declaration_migration
                    (migration_id, tenant_id, declaration_kind, declaration_key, declaration_revision,
                     sql_text, status, created_at, created_by_subject_id, updated_at)
                VALUES (?, 't1', 'entity', 'demo', 1, ?, 'PENDING', CURRENT_TIMESTAMP, 's1', CURRENT_TIMESTAMP)
                """,
                "mig-" + System.nanoTime(),
                big));
    }

    private static void assertFlywayAtLeast(DataSource source, int version) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Integer current = jdbc.query(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC",
                rs -> rs.next() ? Integer.parseInt(rs.getString(1)) : null);
        assertTrue(current != null && current >= version, "expected flyway >= " + version + " got " + current);
    }
}
