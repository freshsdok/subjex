package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V32 / SHARED-1 — PostgreSQL: {@code entity_record.attrs} → {@code jsonb} + GIN index.
 * <p>
 * Fresh installs still create VARCHAR in V30 (dual-mode H2/MySQL/PG scripts). This migration
 * upgrades live PostgreSQL to native jsonb and adds {@code USING GIN (attrs jsonb_path_ops)} for
 * containment {@code @>} filters (hybrid + document). MySQL/H2: no-op (keep VARCHAR JSON text).
 * V30 仍用 VARCHAR 兼顾双模式；本片仅升级真实 PostgreSQL 为 jsonb 并建 GIN（jsonb_path_ops，利于 @>）。
 * MySQL/H2 无操作。
 */
public class V32__EntityRecordAttrsJsonbGin extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData meta = connection.getMetaData();
        String product = meta.getDatabaseProductName() == null ? "" : meta.getDatabaseProductName().toLowerCase();
        try (Statement statement = connection.createStatement()) {
            if (product.contains("postgresql")) {
                migratePostgresql(statement, connection);
                return;
            }
            if (product.contains("mysql")
                    || product.contains("mariadb")
                    || product.contains("h2")) {
                // Dual-mode / MySQL: leave VARCHAR JSON text; GIN is PostgreSQL-only.
                // 双模式与 MySQL：保留 VARCHAR JSON 文本；GIN 仅 PostgreSQL。
                return;
            }
            throw new IllegalStateException("unsupported database for V32: " + product);
        }
    }

    private static void migratePostgresql(Statement statement, Connection connection) throws Exception {
        if (!isJsonbAlready(connection)) {
            // Safe cast: attrs always stores JSON object text from HybridEntityStore.
            // 安全转换：attrs 始终为 HybridEntityStore 写入的 JSON 对象文本。
            statement.execute(
                    "ALTER TABLE entity_record ALTER COLUMN attrs TYPE jsonb USING attrs::jsonb");
        }
        statement.execute(
                """
                CREATE INDEX IF NOT EXISTS entity_record_attrs_gin
                  ON entity_record USING GIN (attrs jsonb_path_ops)
                """);
    }

    /**
     * Idempotent when attrs is already jsonb (re-run / partial apply) —
     * 列已是 jsonb 时幂等（重跑 / 半应用）。
     */
    private static boolean isJsonbAlready(Connection connection) throws Exception {
        try (ResultSet rs = connection
                .createStatement()
                .executeQuery(
                        """
                        SELECT data_type FROM information_schema.columns
                         WHERE table_schema = current_schema()
                           AND table_name = 'entity_record'
                           AND column_name = 'attrs'
                        """)) {
            if (!rs.next()) {
                return false;
            }
            String type = rs.getString(1);
            return type != null && type.equalsIgnoreCase("jsonb");
        }
    }
}
