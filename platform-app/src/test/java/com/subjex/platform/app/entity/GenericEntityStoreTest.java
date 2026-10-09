package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.security.H2PlatformTables;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GenericEntityStoreTest — 通用实体存储：demo-ticket 与 service-note 可保存、查找、列表、删除与 upsert；校验拒绝未知键。
 */
class GenericEntityStoreTest {

    private RenderedEntity demoTicket;
    private RenderedEntity serviceNote;

    @BeforeEach
    void loadCatalog() {
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        demoTicket = catalog.find("demo-ticket").orElseThrow();
        serviceNote = catalog.find("service-note").orElseThrow();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void savesFindsListsDeletesAndUpserts(H2PlatformTables.Mode mode) {
        GenericEntityStore store = new GenericEntityStore(new JdbcTemplate(H2PlatformTables.migrated(mode)));
        assertTrue(store.list(demoTicket, 10).isEmpty());

        store.save(demoTicket, ticket("t-b", "Beta", "open", "u-1", null));
        store.save(demoTicket, ticket("t-a", "Alpha", "open", null, "org-1"));
        List<Map<String, Object>> listed = store.list(demoTicket, 10);
        assertEquals(2, listed.size());
        assertEquals("t-a", listed.get(0).get("ticketId"));
        assertEquals("t-b", listed.get(1).get("ticketId"));
        assertEquals("Alpha", store.findById(demoTicket, "t-a").orElseThrow().get("title"));
        assertEquals("org-1", store.findById(demoTicket, "t-a").orElseThrow().get("organization"));
        assertEquals("u-1", store.findById(demoTicket, "t-b").orElseThrow().get("assignee"));

        store.save(demoTicket, ticket("t-a", "Alpha-2", "closed", "u-2", "org-2"));
        Map<String, Object> again = store.findById(demoTicket, "t-a").orElseThrow();
        assertEquals("Alpha-2", again.get("title"));
        assertEquals("closed", again.get("status"));
        assertEquals("u-2", again.get("assignee"));
        assertEquals("org-2", again.get("organization"));
        assertEquals(2, store.list(demoTicket, 10).size());

        assertTrue(store.deleteById(demoTicket, "t-b"));
        assertFalse(store.findById(demoTicket, "t-b").isPresent());
        assertEquals(1, store.list(demoTicket, 10).size());
        assertFalse(store.deleteById(demoTicket, "missing"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void serviceNoteSavesFindsListsAndUpserts(H2PlatformTables.Mode mode) {
        GenericEntityStore store = new GenericEntityStore(new JdbcTemplate(H2PlatformTables.migrated(mode)));
        assertTrue(store.list(serviceNote, 10).isEmpty());

        store.save(serviceNote, note("n-b", "Beta", "body-b", 2));
        store.save(serviceNote, note("n-a", "Alpha", null, null));
        List<Map<String, Object>> listed = store.list(serviceNote, 10);
        assertEquals(2, listed.size());
        assertEquals("n-a", listed.get(0).get("noteId"));
        assertEquals("n-b", listed.get(1).get("noteId"));
        assertEquals("Alpha", store.findById(serviceNote, "n-a").orElseThrow().get("title"));
        assertNull(store.findById(serviceNote, "n-a").orElseThrow().get("body"));
        assertNull(store.findById(serviceNote, "n-a").orElseThrow().get("priority"));
        assertEquals("body-b", store.findById(serviceNote, "n-b").orElseThrow().get("body"));
        assertEquals(2, store.findById(serviceNote, "n-b").orElseThrow().get("priority"));

        store.save(serviceNote, note("n-a", "Alpha-2", "updated", 9));
        Map<String, Object> again = store.findById(serviceNote, "n-a").orElseThrow();
        assertEquals("Alpha-2", again.get("title"));
        assertEquals("updated", again.get("body"));
        assertEquals(9, again.get("priority"));
        assertEquals(2, store.list(serviceNote, 10).size());
    }

    @Test
    void rejectsUnknownAndMissingRequiredFields() {
        GenericEntityStore store =
                new GenericEntityStore(new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL)));
        Map<String, Object> unknown = ticket("t-1", "Title", "open", null, null);
        unknown.put("extra", "nope");
        assertThrows(IllegalArgumentException.class, () -> store.save(demoTicket, unknown));

        Map<String, Object> missingTitle = new LinkedHashMap<>();
        missingTitle.put("ticketId", "t-1");
        missingTitle.put("status", "open");
        assertThrows(IllegalArgumentException.class, () -> store.save(demoTicket, missingTitle));

        Map<String, Object> noteUnknown = note("n-1", "Title", null, null);
        noteUnknown.put("extra", "nope");
        assertThrows(IllegalArgumentException.class, () -> store.save(serviceNote, noteUnknown));
    }

    private static Map<String, Object> ticket(
            String ticketId, String title, String status, String assignee, String organization) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("ticketId", ticketId);
        row.put("title", title);
        row.put("status", status);
        row.put("assignee", assignee);
        row.put("organization", organization);
        return row;
    }

    private static Map<String, Object> note(String noteId, String title, String body, Integer priority) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("noteId", noteId);
        row.put("title", title);
        row.put("body", body);
        row.put("priority", priority);
        return row;
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void listsWithSortFilterAndRejectsUnknown(H2PlatformTables.Mode mode) {
        GenericEntityStore store = new GenericEntityStore(new JdbcTemplate(H2PlatformTables.migrated(mode)));
        store.save(demoTicket, ticket("t-b", "Beta", "open", "u-1", null));
        store.save(demoTicket, ticket("t-a", "Alpha", "closed", null, "org-1"));
        store.save(demoTicket, ticket("t-c", "Gamma", "open", "u-2", "org-2"));

        List<Map<String, Object>> byTitleDesc =
                store.list(demoTicket, 10, "title", false, null, null);
        assertEquals(List.of("t-c", "t-b", "t-a"), byTitleDesc.stream().map(r -> r.get("ticketId")).toList());

        List<Map<String, Object>> openOnly =
                store.list(demoTicket, 10, "ticketId", true, "status", "open");
        assertEquals(2, openOnly.size());
        assertEquals("open", openOnly.get(0).get("status"));
        assertEquals("open", openOnly.get(1).get("status"));

        List<Map<String, Object>> filteredSorted =
                store.list(demoTicket, 10, "title", false, "status", "open");
        assertEquals(List.of("t-c", "t-b"), filteredSorted.stream().map(r -> r.get("ticketId")).toList());

        assertThrows(IllegalArgumentException.class, () -> store.list(demoTicket, 10, "nope", true, null, null));
        assertThrows(
                IllegalArgumentException.class, () -> store.list(demoTicket, 10, null, true, "nope", "x"));
        assertThrows(
                IllegalArgumentException.class, () -> store.list(demoTicket, 10, null, true, "status", null));
        assertThrows(
                IllegalArgumentException.class, () -> store.list(demoTicket, 10, null, true, null, "open"));
    }

    @Test
    void filterCoercesBooleanAndInteger() {
        RenderedEntity entity = new EntityRenderer().render("""
                entityKey: filter-kinds
                tableName: filter_kinds
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: urgent
                    kind: boolean
                    required: true
                  - name: priority
                    kind: integer
                    required: false
                """);
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        jdbc.execute(
                "CREATE TABLE filter_kinds (id VARCHAR(32) PRIMARY KEY, urgent BOOLEAN NOT NULL, priority INTEGER)");
        GenericEntityStore store = new GenericEntityStore(jdbc);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("id", "a");
        a.put("urgent", true);
        a.put("priority", 1);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("id", "b");
        b.put("urgent", false);
        b.put("priority", 2);
        store.save(entity, a);
        store.save(entity, b);

        List<Map<String, Object>> urgent =
                store.list(entity, 10, null, true, "urgent", "TRUE");
        assertEquals(1, urgent.size());
        assertEquals("a", urgent.get(0).get("id"));

        List<Map<String, Object>> byPri =
                store.list(entity, 10, "priority", false, "priority", "2");
        assertEquals(1, byPri.size());
        assertEquals("b", byPri.get(0).get("id"));

        assertThrows(IllegalArgumentException.class, () -> store.list(entity, 10, null, true, "urgent", "yes"));
        assertThrows(IllegalArgumentException.class, () -> store.list(entity, 10, null, true, "priority", "x"));
    }

        @Test
    void validateWriteAcceptsAndRejectsBooleanEnumDate() {
        RenderedEntity entity = new EntityRenderer().render("""
                entityKey: validate-kinds
                tableName: validate_kinds
                version: 1
                permission: page.read
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: urgent
                    kind: boolean
                    required: true
                  - name: status
                    kind: enum
                    required: true
                    enumValues: [open, closed]
                  - name: dueDate
                    kind: date
                    required: false
                """);
        GenericEntityStore store =
                new GenericEntityStore(new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL)));
        Map<String, Object> ok = new LinkedHashMap<>();
        ok.put("id", "x-1");
        ok.put("urgent", true);
        ok.put("status", "open");
        ok.put("dueDate", "2026-10-08");
        Map<String, Object> accepted = store.validateWrite(entity, ok);
        assertEquals(Boolean.TRUE, accepted.get("urgent"));
        assertEquals("open", accepted.get("status"));
        assertEquals("2026-10-08", accepted.get("dueDate"));

        Map<String, Object> boolString = new LinkedHashMap<>(ok);
        boolString.put("urgent", "false");
        assertEquals(Boolean.FALSE, store.validateWrite(entity, boolString).get("urgent"));

        Map<String, Object> badBool = new LinkedHashMap<>(ok);
        badBool.put("urgent", "yes");
        assertThrows(IllegalArgumentException.class, () -> store.validateWrite(entity, badBool));

        Map<String, Object> badEnum = new LinkedHashMap<>(ok);
        badEnum.put("status", "pending");
        assertThrows(IllegalArgumentException.class, () -> store.validateWrite(entity, badEnum));

        Map<String, Object> badDate = new LinkedHashMap<>(ok);
        badDate.put("dueDate", "08-10-2026");
        assertThrows(IllegalArgumentException.class, () -> store.validateWrite(entity, badDate));

        Map<String, Object> badDateType = new LinkedHashMap<>(ok);
        badDateType.put("dueDate", 20261008);
        assertThrows(IllegalArgumentException.class, () -> store.validateWrite(entity, badDateType));
    }


    @Test
    void tenantScopedIsolatesRowsAndIgnoresBodyTenantOverride() {
        RenderedEntity entity = new EntityRenderer().render("""
                entityKey: scoped-item
                tableName: scoped_item
                version: 1
                permission: page.read
                tenantScoped: true
                fields:
                  - name: id
                    kind: text
                    required: true
                    maxLength: 32
                  - name: title
                    kind: text
                    required: true
                    maxLength: 64
                """);
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        jdbc.execute(
                "CREATE TABLE scoped_item (id VARCHAR(32) NOT NULL, title VARCHAR(64) NOT NULL, "
                        + "tenant_id VARCHAR(64) NOT NULL, PRIMARY KEY (id, tenant_id))");
        GenericEntityStore store = new GenericEntityStore(jdbc);

        Map<String, Object> bodyA = new LinkedHashMap<>();
        bodyA.put("id", "r1");
        bodyA.put("title", "Acme row");
        bodyA.put("tenantId", "other"); // must be ignored — header wins
        store.save(entity, bodyA, "tenant-a");

        Map<String, Object> bodyB = new LinkedHashMap<>();
        bodyB.put("id", "r2");
        bodyB.put("title", "Beta row");
        store.save(entity, bodyB, "tenant-b");

        List<Map<String, Object>> listA = store.list(entity, 10, null, true, null, null, "tenant-a");
        assertEquals(1, listA.size());
        assertEquals("r1", listA.get(0).get("id"));
        assertEquals("Acme row", listA.get(0).get("title"));

        List<Map<String, Object>> listB = store.list(entity, 10, null, true, null, null, "tenant-b");
        assertEquals(1, listB.size());
        assertEquals("r2", listB.get(0).get("id"));

        assertTrue(store.findById(entity, "r1", "tenant-a").isPresent());
        assertFalse(store.findById(entity, "r1", "tenant-b").isPresent());
        assertFalse(store.deleteById(entity, "r1", "tenant-b"));
        assertTrue(store.findById(entity, "r1", "tenant-a").isPresent());

        String stamped = jdbc.queryForObject(
                "SELECT tenant_id FROM scoped_item WHERE id = ?", String.class, "r1");
        assertEquals("tenant-a", stamped);

        assertThrows(IllegalArgumentException.class, () -> store.save(entity, bodyA, null));
        assertThrows(IllegalArgumentException.class, () -> store.list(entity, 10, null, true, null, null, "  "));
    }

}
