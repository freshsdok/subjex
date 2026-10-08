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
 * EffectiveDeclarationServiceTest — DB 草稿覆盖 classpath：demo-ticket title.maxLength 以草稿为准；
 * runtimeEntity 安全覆盖（表名/主键一致才覆盖，否则 409）。
 */
class EffectiveDeclarationServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T12:00:00Z"), ZoneOffset.UTC);

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void draftOverlaysClasspathEntity(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, catalog, new FormCatalog(), new PageCatalog());

        RenderedEntity classpath = catalog.find("demo-ticket").orElseThrow();
        Integer classpathTitleMax = fieldMax(classpath, "title");
        assertEquals(200, classpathTitleMax);

        RenderedEntity before = effective.effectiveEntity("acme", "demo-ticket").orElseThrow();
        assertEquals(classpathTitleMax, fieldMax(before, "title"));
        assertFalse(effective.hasEntityDraft("acme", "demo-ticket"));

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
                  - name: assignee
                    kind: userRef
                    required: false
                    maxLength: 64
                  - name: orgUnit
                    kind: orgRef
                    required: false
                    maxLength: 64
                """;
        store.saveDraft("acme", DeclarationKind.ENTITY, "demo-ticket", draftYaml, "sub-editor");

        RenderedEntity overlaid = effective.effectiveEntity("acme", "demo-ticket").orElseThrow();
        assertEquals(2, overlaid.version());
        assertEquals(80, fieldMax(overlaid, "title"));
        assertTrue(effective.hasEntityDraft("acme", "demo-ticket"));

        // Other tenant still sees classpath — 其他租户仍见 classpath。
        RenderedEntity other = effective.effectiveEntity("other", "demo-ticket").orElseThrow();
        assertEquals(200, fieldMax(other, "title"));
        assertEquals(1, other.version());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void runtimeEntityOverlaysWhenTableAndPkMatch(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcDeclarationStore store = new JdbcDeclarationStore(jdbc, CLOCK);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, catalog, new FormCatalog(), new PageCatalog());

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
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, catalog, new FormCatalog(), new PageCatalog());

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
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        EffectiveDeclarationService effective =
                new EffectiveDeclarationService(store, catalog, new FormCatalog(), new PageCatalog());

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

    private static Integer fieldMax(RenderedEntity entity, String name) {
        Map<String, Integer> byName = entity.fields().stream()
                .collect(Collectors.toMap(EntityField::name, EntityField::maxLength, (a, b) -> a));
        return byName.get(name);
    }
}
