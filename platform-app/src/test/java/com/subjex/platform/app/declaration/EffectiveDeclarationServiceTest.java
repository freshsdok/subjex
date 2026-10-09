package com.subjex.platform.app.declaration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.EntityField;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.page.PageCatalog;
import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * EffectiveDeclarationServiceTest — DRAFT > PROMOTED > classpath 热加载顺序；
 * runtimeEntity：classpath 安全覆盖；无 classpath 仅 PROMOTED+APPLIED 迁移。
 */
class EffectiveDeclarationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    private static final String ENTITY_YAML_TEMPLATE =
            """
            entityKey: demo-ticket
            tableName: demo_ticket
            version: %d
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
                maxLength: %d
              - name: status
                kind: text
                required: true
                maxLength: 32
              - name: assignee
                kind: userRef
                required: false
                maxLength: 64
              - name: orgUnit
                kind: orgRef
                required: false
                maxLength: 64
            """;

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void draftOverlaysClasspathEntity(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        RenderedEntity classpath = catalog.find("demo-ticket").orElseThrow();
        Integer classpathTitleMax = fieldMax(classpath, "title");
        assertEquals(200, classpathTitleMax);

        RenderedEntity before = effective.effectiveEntity("acme", "demo-ticket").orElseThrow();
        assertEquals(classpathTitleMax, fieldMax(before, "title"));
        assertFalse(effective.hasEntityDraft("acme", "demo-ticket"));
        assertEquals(
                EffectiveDeclarationService.SOURCE_CLASSPATH,
                effective.resolutionSource("acme", DeclarationKind.ENTITY, "demo-ticket"));

        store.saveDraft(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                ENTITY_YAML_TEMPLATE.formatted(2, 80),
                "sub-editor");

        RenderedEntity overlaid = effective.effectiveEntity("acme", "demo-ticket").orElseThrow();
        assertEquals(2, overlaid.version());
        assertEquals(80, fieldMax(overlaid, "title"));
        assertTrue(effective.hasEntityDraft("acme", "demo-ticket"));
        assertEquals(
                EffectiveDeclarationService.SOURCE_DRAFT,
                effective.resolutionSource("acme", DeclarationKind.ENTITY, "demo-ticket"));

        // Other tenant still sees classpath — 其他租户仍见 classpath。
        RenderedEntity other = effective.effectiveEntity("other", "demo-ticket").orElseThrow();
        assertEquals(200, fieldMax(other, "title"));
        assertEquals(1, other.version());
        assertEquals(
                EffectiveDeclarationService.SOURCE_CLASSPATH,
                effective.resolutionSource("other", DeclarationKind.ENTITY, "demo-ticket"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void promotedHotReloadsThenDraftBeatsPromoted(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        DeclarationRevision draft = store.saveDraft(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                ENTITY_YAML_TEMPLATE.formatted(2, 80),
                "sub-editor");
        assertEquals(1, draft.revision());
        assertEquals(80, fieldMax(effective.effectiveEntity("acme", "demo-ticket").orElseThrow(), "title"));
        assertTrue(effective.hasEntityDraft("acme", "demo-ticket"));

        // Promote without restart → PROMOTED still overlays classpath (hot-reload).
        // 晋升后无需重启 → PROMOTED 仍覆盖 classpath（热加载）。
        store.markPromoted("acme", DeclarationKind.ENTITY, "demo-ticket", 1);
        store.recordPromote("acme", DeclarationKind.ENTITY, "demo-ticket", 1, "sha-hot", "sub-editor");

        assertFalse(effective.hasEntityDraft("acme", "demo-ticket"));
        assertEquals(
                EffectiveDeclarationService.SOURCE_PROMOTED,
                effective.resolutionSource("acme", DeclarationKind.ENTITY, "demo-ticket"));
        RenderedEntity promoted = effective.effectiveEntity("acme", "demo-ticket").orElseThrow();
        assertEquals(80, fieldMax(promoted, "title"));
        assertEquals(2, promoted.version());
        assertEquals(80, fieldMax(effective.runtimeEntity("acme", "demo-ticket").orElseThrow(), "title"));

        // Newer open DRAFT beats PROMOTED — 更新的未晋升草稿压过已晋升。
        store.saveDraft(
                "acme",
                DeclarationKind.ENTITY,
                "demo-ticket",
                ENTITY_YAML_TEMPLATE.formatted(3, 60),
                "sub-editor");
        assertTrue(effective.hasEntityDraft("acme", "demo-ticket"));
        assertEquals(
                EffectiveDeclarationService.SOURCE_DRAFT,
                effective.resolutionSource("acme", DeclarationKind.ENTITY, "demo-ticket"));
        assertEquals(60, fieldMax(effective.effectiveEntity("acme", "demo-ticket").orElseThrow(), "title"));
        assertEquals(60, fieldMax(effective.runtimeEntity("acme", "demo-ticket").orElseThrow(), "title"));

        // No / blank tenant header → classpath only — 无/空租户头 → 仅 classpath。
        assertEquals(200, fieldMax(effective.runtimeEntity(null, "demo-ticket").orElseThrow(), "title"));
        assertEquals(200, fieldMax(effective.runtimeEntity("  ", "demo-ticket").orElseThrow(), "title"));
        assertEquals(200, fieldMax(effective.effectiveEntity("other", "demo-ticket").orElseThrow(), "title"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void runtimeEntityOverlaysWhenTableAndPkMatch(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        // No tenant header → classpath — 无租户头 → classpath。
        assertEquals(200, fieldMax(effective.runtimeEntity(null, "demo-ticket").orElseThrow(), "title"));

        String draftYaml =
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
                    maxLength: 80
                  - name: status
                    kind: text
                    required: true
                    maxLength: 32
                """;
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", draftYaml, "sub-editor");

        RenderedEntity overlaid = effective.runtimeEntity("acme", "demo-ticket").orElseThrow();
        assertEquals(80, fieldMax(overlaid, "title"));
        assertEquals(2, overlaid.version());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void runtimeEntityRejectsTableNameOrPkChange(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        String badTable =
                """
                entityKey: demo-ticket
                tableName: other_table
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
                    maxLength: 80
                """;
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", badTable, "sub-editor");
        assertThrows(
                DeclarationOverlayConflict.class, () -> effective.runtimeEntity("acme", "demo-ticket"));

        String badPk =
                """
                entityKey: demo-ticket
                tableName: demo_ticket
                version: 3
                permission: page.read
                tenantScoped: false
                fields:
                  - name: otherId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 80
                """;
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", badPk, "sub-editor");
        assertThrows(
                DeclarationOverlayConflict.class, () -> effective.runtimeEntity("acme", "demo-ticket"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void runtimeEntityIgnoresDraftOnlyKeys(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        String draftOnly =
                """
                entityKey: draft-only-entity
                tableName: draft_only
                version: 1
                permission: page.read
                tenantScoped: false
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 64
                """;
        store.saveDraft("acme", DeclarationKind.ENTITY, "draft-only-entity", draftOnly, "sub-editor");
        assertTrue(effective.effectiveEntity("acme", "draft-only-entity").isPresent());
        assertTrue(effective.runtimeEntity("acme", "draft-only-entity").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void runtimeEntityAllowsPromotedNoClasspathWhenMigrationApplied(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        String yaml =
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
        DeclarationRevision draft =
                store.saveDraft("acme", DeclarationKind.ENTITY, "repair-ticket", yaml, "sub-editor");
        DeclarationMigration job = migrations.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "repair-ticket",
                draft.revision(),
                "CREATE TABLE repair_ticket (ticket_id VARCHAR(64) NOT NULL, title VARCHAR(120) NOT NULL, tenant_id VARCHAR(64) NOT NULL)",
                "sub-editor");
        migrations.markReviewed(job.migrationId());
        migrations.markApplied(job.migrationId());
        store.markPromoted("acme", DeclarationKind.ENTITY, "repair-ticket", draft.revision());
        store.recordPromote("acme", DeclarationKind.ENTITY, "repair-ticket", draft.revision(), "sha-rt1", "sub-editor");

        RenderedEntity runtime = effective.runtimeEntity("acme", "repair-ticket").orElseThrow();
        assertEquals("repair-ticket", runtime.entityKey());
        assertEquals("repair_ticket", runtime.tableName());
        assertEquals(120, fieldMax(runtime, "title"));
        assertTrue(catalog.find("repair-ticket").isEmpty());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void runtimeEntityRejectsPromotedNoClasspathWithoutAppliedMigration(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        String yaml =
                """
                entityKey: repair-ticket
                tableName: repair_ticket
                version: 1
                permission: page.read
                tenantScoped: false
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                """;
        DeclarationRevision draft =
                store.saveDraft("acme", DeclarationKind.ENTITY, "repair-ticket", yaml, "sub-editor");
        store.markPromoted("acme", DeclarationKind.ENTITY, "repair-ticket", draft.revision());
        store.recordPromote("acme", DeclarationKind.ENTITY, "repair-ticket", draft.revision(), "sha-meta", "sub-editor");

        // Promoted but no APPLIED migration → 409 fail-closed for JDBC.
        assertThrows(
                DeclarationPromoteBlockedByMigration.class,
                () -> effective.runtimeEntity("acme", "repair-ticket"));

        DeclarationMigration pending = migrations.enqueue(
                "acme",
                DeclarationKind.ENTITY,
                "repair-ticket",
                draft.revision(),
                "CREATE TABLE repair_ticket (ticket_id VARCHAR(64) NOT NULL)",
                "sub-editor");
        assertThrows(
                DeclarationPromoteBlockedByMigration.class,
                () -> effective.runtimeEntity("acme", "repair-ticket"));

        migrations.markReviewed(pending.migrationId());
        assertThrows(
                DeclarationPromoteBlockedByMigration.class,
                () -> effective.runtimeEntity("acme", "repair-ticket"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void effectiveFormResolvesPromotedNoClasspath(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        String formYaml =
                """
                formKey: repair-ticket
                titleEn: Repair ticket
                titleZh: 报修单
                version: 1
                permission: page.read
                tenantScoped: true
                domainAction: entity.record.upsert
                entityKey: repair-ticket
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
        DeclarationRevision draft =
                store.saveDraft("acme", DeclarationKind.FORM, "repair-ticket", formYaml, "sub-editor");
        store.markPromoted("acme", DeclarationKind.FORM, "repair-ticket", draft.revision());
        store.recordPromote("acme", DeclarationKind.FORM, "repair-ticket", draft.revision(), "sha-form", "sub-editor");

        var form = effective.effectiveForm("acme", "repair-ticket").orElseThrow();
        assertEquals("repair-ticket", form.formKey());
        assertEquals(1, form.version());
        assertEquals("报修单", form.titleZh());
        assertEquals(
                EffectiveDeclarationService.SOURCE_PROMOTED,
                effective.resolutionSource("acme", DeclarationKind.FORM, "repair-ticket"));
    }


    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void effectiveRuntimePagesAfterFlowPromote(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        JdbcDeclarationMigrationStore migrations = new JdbcDeclarationMigrationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, migrations, catalog, new FormCatalog(), new PageCatalog());

        String flowYaml =
                """
                flowKey: repair-ticket
                titleEn: Repair tickets
                titleZh: 报修单
                formKey: repair-ticket
                entityKey: repair-ticket
                version: 1
                permission: page.read
                tenantScoped: true
                list:
                  path: /pages/repair-ticket
                  apiPath: /api/v1/entities/repair-ticket/records
                  itemsKey: records
                  blocks:
                    - ListTable
                detail:
                  path: /pages/repair-ticket/{id}
                  apiPath: /api/v1/entities/repair-ticket/records
                  itemsKey: records
                  idField: ticketId
                  blocks:
                    - DetailReadonly
                submit:
                  path: /pages/repair-ticket/new
                  apiPath: /api/v1/forms/repair-ticket/submissions
                  redirectTo: /pages/repair-ticket
                  blocks:
                    - FormFields
                    - SubmitBar
                """;
        assertTrue(effective.effectiveRuntimePages("acme", "repair-ticket").isEmpty());
        assertTrue(new PageCatalog().find("repair-ticket").isEmpty());

        DeclarationRevision draft =
                store.saveDraft("acme", DeclarationKind.FLOW, "repair-ticket", flowYaml, "sub-editor");
        store.markPromoted("acme", DeclarationKind.FLOW, "repair-ticket", draft.revision());
        store.recordPromote("acme", DeclarationKind.FLOW, "repair-ticket", draft.revision(), "sha-rt5", "sub-editor");

        DeclarationRuntimePages.Binding pages =
                effective.effectiveRuntimePages("acme", "repair-ticket").orElseThrow();
        assertEquals("repair-ticket", pages.flowKey());
        assertEquals("/pages/repair-ticket", pages.listPath());
        assertEquals("/pages/repair-ticket/new", pages.newPath());
        assertEquals("/pages/repair-ticket/{id}", pages.detailPath());
        assertEquals(
                EffectiveDeclarationService.SOURCE_PROMOTED,
                effective.resolutionSource("acme", DeclarationKind.FLOW, "repair-ticket"));
        assertTrue(effective.tenantDeclarationKeys("acme", DeclarationKind.FLOW).contains("repair-ticket"));
    }

    private static Integer fieldMax(RenderedEntity entity, String name) {
        Map<String, Integer> byName = entity.fields().stream()
                .collect(Collectors.toMap(EntityField::name, EntityField::maxLength, (a, b) -> a));
        return byName.get(name);
    }
}
