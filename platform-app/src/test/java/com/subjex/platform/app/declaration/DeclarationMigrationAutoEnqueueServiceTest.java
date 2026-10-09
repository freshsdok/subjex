package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * DeclarationMigrationAutoEnqueueServiceTest — RT-4：新实体 CREATE、加列 ALTER ADD、幂等、classpath 样例。
 */
class DeclarationMigrationAutoEnqueueServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T02:00:00Z"), ZoneOffset.UTC);

    private static final String REPAIR_YAML =
            """
            entityKey: repair-ticket
            tableName: repair_ticket
            version: 1
            permission: page.read
            tenantScoped: true
            fields:
              - name: ticketId
                kind: text
                required: true
                maxLength: 64
              - name: title
                kind: text
                required: true
                maxLength: 120
            """;

    private static final String REPAIR_YAML_WITH_LOCATION =
            """
            entityKey: repair-ticket
            tableName: repair_ticket
            version: 2
            permission: page.read
            tenantScoped: true
            fields:
              - name: ticketId
                kind: text
                required: true
                maxLength: 64
              - name: title
                kind: text
                required: true
                maxLength: 120
              - name: location
                kind: text
                required: false
                maxLength: 200
            """;

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void newEntityEnqueuesCreateTable(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.open(mode);
        DeclarationRevision draft =
                f.store.saveDraft("acme", DeclarationKind.ENTITY, "repair-ticket", REPAIR_YAML, "sub-a");
        List<String> planned = f.auto.planForEntityYaml("acme", "repair-ticket", REPAIR_YAML);
        assertEquals(1, planned.size());
        assertTrue(planned.get(0).startsWith("CREATE TABLE repair_ticket"));
        assertTrue(planned.get(0).contains("tenant_id VARCHAR(64) NOT NULL"));

        List<DeclarationMigration> jobs = f.auto.enqueuePlanned(draft, planned);
        assertEquals(1, jobs.size());
        assertEquals(JdbcDeclarationMigrationStore.PENDING, jobs.get(0).status());
        assertEquals(draft.revision(), jobs.get(0).declarationRevision());
        assertTrue(jobs.get(0).sqlText().startsWith("CREATE TABLE repair_ticket"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void addFieldVsPromotedEnqueuesAlterAdd(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.open(mode);
        DeclarationRevision r1 =
                f.store.saveDraft("acme", DeclarationKind.ENTITY, "repair-ticket", REPAIR_YAML, "sub-a");
        f.auto.enqueuePlanned(r1, f.auto.planForEntityYaml("acme", "repair-ticket", REPAIR_YAML));
        // Settle + promote so baseline becomes PROMOTED (skip real DDL apply for this unit test).
        // 结清并晋升，使基线变为 PROMOTED（本测不跑真实 DDL）。
        DeclarationMigration create = f.migrations.listForRevision(
                        "acme", DeclarationKind.ENTITY, "repair-ticket", r1.revision())
                .get(0);
        f.migrations.markReviewed(create.migrationId());
        f.migrations.markApplied(create.migrationId());
        f.store.markPromoted("acme", DeclarationKind.ENTITY, "repair-ticket", r1.revision());
        f.store.recordPromote(
                "acme", DeclarationKind.ENTITY, "repair-ticket", r1.revision(), "sha-rt4", "sub-a");

        DeclarationRevision r2 = f.store.saveDraft(
                "acme", DeclarationKind.ENTITY, "repair-ticket", REPAIR_YAML_WITH_LOCATION, "sub-a");
        List<String> planned = f.auto.planForEntityYaml("acme", "repair-ticket", REPAIR_YAML_WITH_LOCATION);
        assertEquals(1, planned.size());
        assertEquals("ALTER TABLE repair_ticket ADD COLUMN location VARCHAR(200)", planned.get(0));

        List<DeclarationMigration> jobs = f.auto.enqueuePlanned(r2, planned);
        assertEquals(1, jobs.size());
        assertEquals(JdbcDeclarationMigrationStore.PENDING, jobs.get(0).status());
        assertEquals(r2.revision(), jobs.get(0).declarationRevision());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void reEnqueueSameRevisionIsIdempotent(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.open(mode);
        DeclarationRevision draft =
                f.store.saveDraft("acme", DeclarationKind.ENTITY, "repair-ticket", REPAIR_YAML, "sub-a");
        List<String> planned = f.auto.planForEntityYaml("acme", "repair-ticket", REPAIR_YAML);
        assertEquals(1, f.auto.enqueuePlanned(draft, planned).size());
        assertEquals(0, f.auto.enqueuePlanned(draft, planned).size());
        assertEquals(
                1,
                f.migrations
                        .listForRevision("acme", DeclarationKind.ENTITY, "repair-ticket", draft.revision())
                        .size());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void classpathSampleAddFieldEnqueuesAlterNotCreate(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.open(mode);
        // demo-ticket exists on classpath; no tenant PROMOTED → baseline = classpath.
        // classpath 有 demo-ticket；无租户 PROMOTED → 基线为 classpath。
        String yaml =
                """
                entityKey: demo-ticket
                tableName: demo_ticket
                version: 2
                permission: page.read
                tenantScoped: false
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 200
                  - name: status
                    kind: text
                    required: true
                    maxLength: 32
                  - name: assignee
                    kind: subjectRef
                    required: false
                    maxLength: 64
                  - name: organization
                    kind: organizationRef
                    required: false
                    maxLength: 64
                  - name: priority
                    kind: text
                    required: false
                    maxLength: 32
                """;
        List<String> planned = f.auto.planForEntityYaml("acme", "demo-ticket", yaml);
        assertEquals(1, planned.size());
        assertEquals("ALTER TABLE demo_ticket ADD COLUMN priority VARCHAR(32)", planned.get(0));

        DeclarationRevision draft =
                f.store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", yaml, "sub-a");
        List<DeclarationMigration> jobs = f.auto.enqueuePlanned(draft, planned);
        assertEquals(1, jobs.size());
        assertTrue(jobs.get(0).sqlText().startsWith("ALTER TABLE"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void classpathUnchangedDraftEnqueuesNothing(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.open(mode);
        String yaml =
                """
                entityKey: demo-ticket
                tableName: demo_ticket
                version: 1
                permission: page.read
                tenantScoped: false
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 200
                  - name: status
                    kind: text
                    required: true
                    maxLength: 32
                  - name: assignee
                    kind: subjectRef
                    required: false
                    maxLength: 64
                  - name: organization
                    kind: organizationRef
                    required: false
                    maxLength: 64
                """;
        List<String> planned = f.auto.planForEntityYaml("acme", "demo-ticket", yaml);
        assertTrue(planned.isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void unsafeTypeChangeFailsClosed(H2PlatformTables.Mode mode) {
        Fixture f = Fixture.open(mode);
        DeclarationRevision r1 =
                f.store.saveDraft("acme", DeclarationKind.ENTITY, "repair-ticket", REPAIR_YAML, "sub-a");
        f.auto.enqueuePlanned(r1, f.auto.planForEntityYaml("acme", "repair-ticket", REPAIR_YAML));
        DeclarationMigration create = f.migrations.listForRevision(
                        "acme", DeclarationKind.ENTITY, "repair-ticket", r1.revision())
                .get(0);
        f.migrations.markReviewed(create.migrationId());
        f.migrations.markApplied(create.migrationId());
        f.store.markPromoted("acme", DeclarationKind.ENTITY, "repair-ticket", r1.revision());
        f.store.recordPromote(
                "acme", DeclarationKind.ENTITY, "repair-ticket", r1.revision(), "sha-rt4b", "sub-a");

        String bad =
                """
                entityKey: repair-ticket
                tableName: repair_ticket
                version: 2
                permission: page.read
                tenantScoped: true
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: integer
                    required: true
                """;
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> f.auto.planForEntityYaml("acme", "repair-ticket", bad));
        assertTrue(ex.getMessage().contains("field kind") || ex.getMessage().contains("SQL type"));
    }

    private record Fixture(
            JdbcDeclarationStore store,
            JdbcDeclarationMigrationStore migrations,
            DeclarationMigrationAutoEnqueueService auto) {
        static Fixture open(H2PlatformTables.Mode mode) {
            JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
            JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
            JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
            EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
            DeclarationMigrationAutoEnqueueService auto =
                    new DeclarationMigrationAutoEnqueueService(store, migrations, catalog);
            return new Fixture(store, migrations, auto);
        }
    }
}
