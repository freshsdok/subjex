package com.subjex.platform.app.connection;

import com.subjex.platform.contract.connection.ConnectionVendor;
import com.subjex.platform.contract.connection.RelationalConnectionPort;
import java.sql.Connection;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * MigrationGuard — 迁移门禁：库结构不是成功状态，或厂商与配置不一致时，拒绝提供服务。
 * <p>
 * Flyway applies the shared script. This runner then refuses pending migrations and a product-name mismatch.
 * Flyway 执行共用脚本。随后这个启动步骤拒绝仍待执行的迁移，也拒绝产品名不匹配。
 */
@Component
public final class MigrationGuard implements ApplicationRunner {

    private final Flyway flyway;
    private final RelationalConnectionPort connectionPort;

    public MigrationGuard(Flyway flyway, RelationalConnectionPort connectionPort) {
        this.flyway = flyway;
        this.connectionPort = connectionPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        MigrationInfo current = flyway.info().current();
        if (current == null || current.getState() != MigrationState.SUCCESS) {
            throw new IllegalStateException("schema migration is not SUCCESS; refusing to serve");
        }
        if (flyway.info().pending().length > 0) {
            throw new IllegalStateException("pending schema migrations remain; refusing to serve");
        }
        try (Connection connection = connectionPort.connectionSource().getConnection()) {
            String productName = connection.getMetaData().getDatabaseProductName();
            ConnectionVendor live = ConnectionVendor.fromProductName(productName);
            if (live != connectionPort.vendor()) {
                throw new IllegalStateException(
                        "configured vendor " + connectionPort.vendor() + " but live product is " + productName);
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("could not verify the relational connection", ex);
        }
    }
}
