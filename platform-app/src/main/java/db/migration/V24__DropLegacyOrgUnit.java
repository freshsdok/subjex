package db.migration;

import com.subjex.platform.app.organization.OrganizationOntologyBackfill;
import java.sql.Statement;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/**
 * O7: backfill any remaining org_unit rows into the ontology, then DROP legacy tables.
 * Keeps {@code org_unit_organization_map}. Fails closed if any unit is still unmapped.
 * O7：先回填再删除旧表；保留映射表；有未映射行则失败。
 */
public class V24__DropLegacyOrgUnit extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        SingleConnectionDataSource dataSource =
                new SingleConnectionDataSource(context.getConnection(), true);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        OrganizationOntologyBackfill backfill = new OrganizationOntologyBackfill(jdbc);
        backfill.backfillAll();

        Integer orphans = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM org_unit u
                WHERE NOT EXISTS (
                    SELECT 1 FROM org_unit_organization_map m
                    WHERE m.tenant_id = u.tenant_id AND m.org_unit_id = u.org_unit_id
                )
                """,
                Integer.class);
        if (orphans != null && orphans > 0) {
            throw new IllegalStateException(
                    "O7 backfill incomplete: " + orphans + " unmapped org_unit row(s)");
        }

        try (Statement statement = context.getConnection().createStatement()) {
            statement.execute("DROP TABLE IF EXISTS org_membership");
            statement.execute("DROP TABLE IF EXISTS org_unit");
        }
    }
}
