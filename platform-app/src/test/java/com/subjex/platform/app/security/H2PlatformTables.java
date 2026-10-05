package com.subjex.platform.app.security;

import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

/**
 * H2PlatformTables — 测试用的 H2 平台表：用真实的共用 Flyway 脚本建一份内存库。
 * <p>
 * H2 runs in PostgreSQL or MySQL compatibility mode. It proves the scripts and the SQL in tests without Docker.
 * It is not the live-vendor proof; {@code VendorStartupTest} still needs Docker for that.
 * H2 以 PostgreSQL 或 MySQL 兼容模式运行，在没有 Docker 的测试里证明脚本和 SQL。
 * 它不是真实厂商的证明；那一项仍由需要 Docker 的 {@code VendorStartupTest} 负责。
 */
public final class H2PlatformTables {

    /** Compatibility mode — 兼容模式。 */
    public enum Mode {
        POSTGRESQL("PostgreSQL"),
        MYSQL("MySQL");

        private final String h2Name;

        Mode(String h2Name) {
            this.h2Name = h2Name;
        }
    }

    private H2PlatformTables() {}

    /** A fresh in-memory database, migrated — 一份新的、已迁移的内存库。 */
    public static DataSource migrated(Mode mode) {
        return migrated("jdbc:h2:mem:subjex-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", mode);
    }

    /** The database at this URL prefix, migrated — 这个地址上的库，已迁移。 */
    public static DataSource migrated(String urlPrefix, Mode mode) {
        DataSource source = dataSource(urlPrefix, mode);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        return source;
    }

    /** Connect without migrating — 只连接，不迁移。 */
    public static DataSource dataSource(String urlPrefix, Mode mode) {
        String url = urlPrefix + ";MODE=" + mode.h2Name + ";DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH";
        return new SimpleDriverDataSource(new org.h2.Driver(), url, "sa", "");
    }
}
