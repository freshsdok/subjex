package db.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

/**
 * V29 — declaration_migration.sql_text → TEXT (MySQL row-size / VendorStartupTest CI).
 * <p>
 * V16 originally used {@code VARCHAR(16000)}, which fails on MySQL 8 utf8mb4 InnoDB row limits.
 * V16 is corrected to TEXT for fresh installs; this migration widens already-applied stores after
 * operators {@code flyway repair} the V16 checksum. Idempotent when the column is already TEXT.
 * V16 原稿 VARCHAR(16000) 在 MySQL 上行过大；新装已改 TEXT。本片在 repair 后把旧库列改成 TEXT；已是 TEXT 则无操作。
 */
public class V29__DeclarationMigrationSqlTextToText extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        DatabaseMetaData meta = connection.getMetaData();
        String product = meta.getDatabaseProductName() == null ? "" : meta.getDatabaseProductName().toLowerCase();
        try (Statement statement = connection.createStatement()) {
            if (product.contains("mysql") || product.contains("mariadb")) {
                statement.execute("ALTER TABLE declaration_migration MODIFY COLUMN sql_text TEXT NOT NULL");
                return;
            }
            if (product.contains("postgresql")) {
                statement.execute("ALTER TABLE declaration_migration ALTER COLUMN sql_text TYPE TEXT");
                return;
            }
            if (product.contains("h2")) {
                // H2 accepts SET DATA TYPE for both PG and MySQL modes used in tests.
                statement.execute("ALTER TABLE declaration_migration ALTER COLUMN sql_text SET DATA TYPE CHARACTER LARGE OBJECT");
                return;
            }
            throw new IllegalStateException("unsupported database for V29: " + product);
        }
    }
}
