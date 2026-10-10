package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
