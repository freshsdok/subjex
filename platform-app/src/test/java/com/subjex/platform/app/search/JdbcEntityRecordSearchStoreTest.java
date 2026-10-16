package com.subjex.platform.app.search;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.tenant.TenantIsolationTags;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * JdbcEntityRecordSearchStoreTest — search read path MVP over entity_record.
 * Gates: tenant isolation; substring on id/attrs; optional field fragment; no writes.
 */
@Tag(TenantIsolationTags.TENANT_ISOLATION)
class JdbcEntityRecordSearchStoreTest {

    private final ObjectMapper json = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("SEARCH-01 substring matches record_id and attrs within tenant")
    void substringWithinTenant(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        seed(jdbc);
        EntityRecordSearchStore store = new JdbcEntityRecordSearchStore(jdbc, json, false);

        List<EntityRecordSearchHit> byTitle = store.search("acme", null, "Alpha", null, null, 20);
        assertEquals(1, byTitle.size());
        assertEquals("a-1", byTitle.get(0).recordId());
        assertEquals("asset", byTitle.get(0).entityKey());

        List<EntityRecordSearchHit> byId = store.search("acme", "asset", "a-2", null, null, 20);
        assertEquals(1, byId.size());
        assertEquals("Beta", previewHas(byId.get(0), "Beta"));

        List<EntityRecordSearchHit> otherTenant = store.search("other", null, "Alpha", null, null, 20);
        assertEquals(1, otherTenant.size());
        assertEquals("other", otherTenant.get(0).tenantId());
        // acme Alpha hit must not leak other-tenant rows
        assertTrue(byTitle.stream().allMatch(h -> "acme".equals(h.tenantId())));

        List<EntityRecordSearchHit> scoped = store.search("acme", "note", "hello", null, null, 20);
        assertEquals(1, scoped.size());
        assertEquals("n-1", scoped.get(0).recordId());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    @DisplayName("SEARCH-02 field+value portable match; q required xor field")
    void fieldValueAndValidation(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        seed(jdbc);
        EntityRecordSearchStore store = new JdbcEntityRecordSearchStore(jdbc, json, false);

        List<EntityRecordSearchHit> hits = store.search("acme", "asset", null, "title", "Beta", 20);
        assertEquals(1, hits.size());
        assertEquals("a-2", hits.get(0).recordId());

        assertThrows(IllegalArgumentException.class, () -> store.search("acme", null, null, null, null, 10));
        assertThrows(IllegalArgumentException.class, () -> store.search("acme", null, "", "title", null, 10));
        assertThrows(IllegalArgumentException.class, () -> store.search("acme", null, "x", null, null, 0));
    }

    private static String previewHas(EntityRecordSearchHit hit, String needle) {
        assertTrue(hit.attrsPreview().contains(needle), hit.attrsPreview());
        return needle;
    }

    private static void seed(JdbcTemplate jdbc) {
        jdbc.update(
                """
                INSERT INTO entity_record
                  (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                "acme",
                "asset",
                "a-1",
                "ACTIVE",
                "{\"title\":\"Alpha\",\"priority\":1}",
                "2026-10-16T00:00:00Z",
                "2026-10-16T01:00:00Z");
        jdbc.update(
                """
                INSERT INTO entity_record
                  (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                "acme",
                "asset",
                "a-2",
                "ACTIVE",
                "{\"title\":\"Beta\",\"priority\":2}",
                "2026-10-16T00:00:00Z",
                "2026-10-16T02:00:00Z");
        jdbc.update(
                """
                INSERT INTO entity_record
                  (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                "acme",
                "note",
                "n-1",
                "ACTIVE",
                "{\"body\":\"hello world\"}",
                "2026-10-16T00:00:00Z",
                "2026-10-16T03:00:00Z");
        jdbc.update(
                """
                INSERT INTO entity_record
                  (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?)
                """,
                "other",
                "asset",
                "a-1",
                "ACTIVE",
                "{\"title\":\"Alpha\"}",
                "2026-10-16T00:00:00Z",
                "2026-10-16T01:00:00Z");
    }
}
