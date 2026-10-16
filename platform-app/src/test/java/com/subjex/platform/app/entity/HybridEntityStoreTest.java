package com.subjex.platform.app.entity;

import com.subjex.platform.app.tenant.TenantIsolationTags;
import org.junit.jupiter.api.Tag;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.declare.EntityCatalog;
import com.subjex.entity.declare.EntityRenderer;
import com.subjex.entity.declare.EntityStorageMode;
import com.subjex.entity.declare.RenderedEntity;
import com.subjex.platform.app.security.H2PlatformTables;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;


/**
 * HybridEntityStoreTest — purpose: ES-1 hybrid insert/list on shared entity_record (ADR 0002).
 * Gates: storageMode=hybrid required; tenantScoped stamps/filters tenant_id; table-mode reject.
 * <p>
 * 目的：ES-1 混合轨在共享 entity_record 上 insert/list。门禁：须 hybrid；租户盖章/过滤；table 轨拒绝。
 */
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class HybridEntityStoreTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-10-10T04:00:00Z"), ZoneOffset.UTC);

    private final EntityRenderer renderer = new EntityRenderer();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void insertsAndListsHybridRows(H2PlatformTables.Mode mode) {
        HybridEntityStore store = newStore(mode);
        RenderedEntity entity = hybridAsset();

        assertTrue(store.list(entity, 10, "tenant-a").isEmpty());

        store.save(entity, asset("a-2", "Beta", 2), "tenant-a");
        store.save(entity, asset("a-1", "Alpha", 1), "tenant-a");
        // Other tenant must not appear — 其他租户不可见
        store.save(entity, asset("a-x", "Other", 9), "tenant-b");

        List<Map<String, Object>> listed = store.list(entity, 10, "tenant-a");
        assertEquals(2, listed.size());
        assertEquals("a-1", listed.get(0).get("assetId"));
        assertEquals("Alpha", listed.get(0).get("title"));
        assertEquals(1, ((Number) listed.get(0).get("priority")).intValue());
        assertEquals("a-2", listed.get(1).get("assetId"));

        Map<String, Object> found = store.findById(entity, "a-1", "tenant-a").orElseThrow();
        assertEquals("Alpha", found.get("title"));
        assertTrue(store.findById(entity, "a-1", "tenant-b").isEmpty());

        store.save(entity, asset("a-1", "Alpha-2", 3), "tenant-a");
        assertEquals("Alpha-2", store.findById(entity, "a-1", "tenant-a").orElseThrow().get("title"));
        assertEquals(3, ((Number) store.findById(entity, "a-1", "tenant-a").orElseThrow().get("priority")).intValue());
    }

    @Test
    void rejectsTableStorageMode() {
        HybridEntityStore store = newStore(H2PlatformTables.Mode.POSTGRESQL);
        RenderedEntity tableEntity = renderer.render(
                """
                entityKey: demo-ticket
                tableName: demo_ticket
                version: 1
                permission: page.read
                storageMode: table
                fields:
                  - name: ticketId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 200
                """);
        assertEquals(EntityStorageMode.TABLE, tableEntity.storageMode());
        assertThrows(
                IllegalArgumentException.class,
                () -> store.save(tableEntity, Map.of("ticketId", "t-1", "title", "x"), null));
    }

    @Test
    void omittedStorageModeDefaultsToHybrid() {
        RenderedEntity entity = renderer.render(
                """
                entityKey: scratch-item
                tableName: scratch_item
                version: 1
                permission: page.read
                fields:
                  - name: itemId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 100
                """);
        assertEquals(EntityStorageMode.HYBRID, entity.storageMode());
        assertTrue(entity.hybridStorage());
    }



    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void classpathSamplesRoundTripOnHybrid(H2PlatformTables.Mode mode) {
        // HY-1: service-note / demo-ticket live on entity_record — 样例落共享表
        HybridEntityStore store = newStore(mode);
        EntityCatalog catalog = EntityCatalog.load(EntityCatalog.class.getClassLoader());
        RenderedEntity note = catalog.find("service-note").orElseThrow();
        RenderedEntity ticket = catalog.find("demo-ticket").orElseThrow();
        assertEquals(EntityStorageMode.HYBRID, note.storageMode());
        assertEquals(EntityStorageMode.HYBRID, ticket.storageMode());

        Map<String, Object> noteRow = new LinkedHashMap<>();
        noteRow.put("noteId", "n-1");
        noteRow.put("title", "Hello");
        noteRow.put("body", "world");
        noteRow.put("priority", 3);
        store.save(note, noteRow, null);
        assertEquals("Hello", store.findById(note, "n-1", null).orElseThrow().get("title"));
        assertEquals(1, store.list(note, 10, null).size());

        Map<String, Object> ticketRow = new LinkedHashMap<>();
        ticketRow.put("ticketId", "t-1");
        ticketRow.put("title", "Open");
        ticketRow.put("status", "new");
        ticketRow.put("assignee", "u-1");
        ticketRow.put("organization", "org-1");
        store.save(ticket, ticketRow, null);
        assertEquals("new", store.findById(ticket, "t-1", null).orElseThrow().get("status"));
        assertEquals(1, store.list(ticket, 10, null).size());
    }




    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void v32MigrationKeepsAttrsReadableOnH2(H2PlatformTables.Mode mode) {
        // SHARED-1: V32 is no-op on H2; CRUD + attrs equality filter (in-memory) still work.
        // SHARED-1：H2 上 V32 无操作；CRUD 与 attrs 等值过滤（内存）仍可用。
        HybridEntityStore store = newStore(mode);
        RenderedEntity entity = hybridAsset();
        store.save(entity, asset("a-1", "Alpha", 1), "tenant-a");
        store.save(entity, asset("a-2", "Beta", 2), "tenant-a");
        var byTitle = store.list(entity, 10, "tenant-a", true, "title", "Alpha");
        assertEquals(1, byTitle.size());
        assertEquals("a-1", byTitle.get(0).get("assetId"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void documentModeRoundTripAllFieldsInAttrs(H2PlatformTables.Mode mode) {
        // DOC-1: document = shared entity_record; all declared fields in attrs JSON.
        // DOC-1：document 共享表；声明字段全在 attrs。
        HybridEntityStore store = newStore(mode);
        RenderedEntity entity = renderer.render(
                """
                entityKey: doc-asset
                tableName: doc_asset
                version: 1
                permission: page.read
                tenantScoped: true
                storageMode: document
                fields:
                  - name: assetId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 200
                  - name: status
                    kind: text
                    required: false
                    maxLength: 32
                """);
        assertEquals(EntityStorageMode.DOCUMENT, entity.storageMode());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assetId", "d-1");
        row.put("title", "Doc");
        row.put("status", "open");
        store.save(entity, row, "tenant-a");

        Map<String, Object> found = store.findById(entity, "d-1", "tenant-a").orElseThrow();
        assertEquals("Doc", found.get("title"));
        assertEquals("open", found.get("status"));
        assertEquals(1, store.list(entity, 10, "tenant-a").size());

        store.save(entity, Map.of("assetId", "d-1", "title", "Doc-2", "status", "closed"), "tenant-a");
        assertEquals("Doc-2", store.findById(entity, "d-1", "tenant-a").orElseThrow().get("title"));
        assertTrue(store.deleteById(entity, "d-1", "tenant-a"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void promotedFieldUsesColumnNotOnlyAttrs(H2PlatformTables.Mode mode) {
        // HY-2: promote:true → typed hp_* column; unpromoted stays in attrs JSON.
        // HY-2：升格字段落物理列；未升格仍在 attrs。
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        jdbc.execute("ALTER TABLE entity_record ADD COLUMN hp_hybrid_asset_status VARCHAR(32)");
        HybridEntityStore store =
                new JdbcHybridEntityStore(jdbc, FIXED, new ObjectMapper());
        RenderedEntity entity = renderer.render(
                """
                entityKey: hybrid-asset
                tableName: hybrid_asset
                version: 1
                permission: page.read
                tenantScoped: true
                storageMode: hybrid
                fields:
                  - name: assetId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 200
                  - name: status
                    kind: text
                    required: false
                    maxLength: 32
                    promote: true
                  - name: note
                    kind: text
                    required: false
                    maxLength: 100
                """);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("assetId", "a-1");
        row.put("title", "Alpha");
        row.put("status", "open");
        row.put("note", "keep-in-attrs");
        store.save(entity, row, "tenant-a");

        Map<String, Object> found = store.findById(entity, "a-1", "tenant-a").orElseThrow();
        assertEquals("open", found.get("status"));
        assertEquals("keep-in-attrs", found.get("note"));
        assertEquals("Alpha", found.get("title"));

        String colStatus = jdbc.queryForObject(
                "SELECT hp_hybrid_asset_status FROM entity_record WHERE record_id = ?",
                String.class,
                "a-1");
        assertEquals("open", colStatus);
        String attrsJson = jdbc.queryForObject(
                "SELECT attrs FROM entity_record WHERE record_id = ?", String.class, "a-1");
        assertTrue(attrsJson.contains("keep-in-attrs"));
        // status stripped from attrs — status 已从 attrs 剥离
        assertFalse(attrsJson.contains("\"status\""));

        List<Map<String, Object>> filtered =
                store.list(entity, 10, "tenant-a", true, "status", "open");
        assertEquals(1, filtered.size());
        assertEquals("a-1", filtered.get(0).get("assetId"));
    }

    @Test
    void filtersByPrimaryKeyAndTitleAndOrdersDesc() {
        HybridEntityStore store = newStore(H2PlatformTables.Mode.POSTGRESQL);
        RenderedEntity entity = hybridAsset();
        store.save(entity, asset("a-2", "Beta", 2), "tenant-a");
        store.save(entity, asset("a-1", "Alpha", 1), "tenant-a");
        store.save(entity, asset("a-3", "Alpha", 3), "tenant-a");

        var byPk = store.list(entity, 10, "tenant-a", true, "assetId", "a-2");
        assertEquals(1, byPk.size());
        assertEquals("a-2", byPk.get(0).get("assetId"));

        var byTitle = store.list(entity, 10, "tenant-a", true, "title", "Alpha");
        assertEquals(2, byTitle.size());
        assertEquals("a-1", byTitle.get(0).get("assetId"));
        assertEquals("a-3", byTitle.get(1).get("assetId"));

        var desc = store.list(entity, 10, "tenant-a", false, null, null);
        assertEquals("a-3", desc.get(0).get("assetId"));
        assertEquals("a-1", desc.get(2).get("assetId"));
    }

    private HybridEntityStore newStore(H2PlatformTables.Mode mode) {
        return new JdbcHybridEntityStore(
                new JdbcTemplate(H2PlatformTables.migrated(mode)), FIXED, new ObjectMapper());
    }

    private RenderedEntity hybridAsset() {
        return renderer.render(
                """
                entityKey: hybrid-asset
                tableName: hybrid_asset
                version: 1
                permission: page.read
                tenantScoped: true
                storageMode: hybrid
                fields:
                  - name: assetId
                    kind: text
                    required: true
                    maxLength: 64
                  - name: title
                    kind: text
                    required: true
                    maxLength: 200
                  - name: priority
                    kind: integer
                    required: false
                """);
    }

    private static Map<String, Object> asset(String id, String title, Integer priority) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("assetId", id);
        values.put("title", title);
        values.put("priority", priority);
        return values;
    }
}
