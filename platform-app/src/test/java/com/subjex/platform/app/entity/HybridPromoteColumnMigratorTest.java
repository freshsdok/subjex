package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * HybridPromoteColumnMigratorTest — HY-2b attrs↔hp_* backfill / merge round-trip on H2.
 * 目的：升格回填与降格写回在 H2 上往返。
 */
class HybridPromoteColumnMigratorTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void backfillThenMergeRoundTrip(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        ObjectMapper json = new ObjectMapper();
        HybridPromoteColumnMigrator migrator = new HybridPromoteColumnMigrator(jdbc, json);

        jdbc.execute("ALTER TABLE entity_record ADD COLUMN hp_demo_ticket_status VARCHAR(32)");
        jdbc.update(
                "INSERT INTO entity_record (tenant_id, entity_key, record_id, record_state, attrs, created_at, updated_at)"
                        + " VALUES ('', 'demo-ticket', 't-1', 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                "{\"status\":\"open\",\"title\":\"Hello\"}");

        int filled = migrator.backfillFromAttrs("demo-ticket", "hp_demo_ticket_status");
        assertEquals(1, filled);
        Map<String, Object> afterAdd = jdbc.queryForMap(
                "SELECT hp_demo_ticket_status AS hp, attrs FROM entity_record WHERE record_id = 't-1'");
        assertEquals("open", afterAdd.get("hp"));
        assertTrue(String.valueOf(afterAdd.get("attrs")).contains("title"));
        assertTrue(!String.valueOf(afterAdd.get("attrs")).contains("status"));

        // Simulate demote merge before DROP
        int merged = migrator.mergeIntoAttrs("demo-ticket", "hp_demo_ticket_status");
        assertEquals(1, merged);
        Map<String, Object> afterMerge = jdbc.queryForMap(
                "SELECT attrs FROM entity_record WHERE record_id = 't-1'");
        assertTrue(String.valueOf(afterMerge.get("attrs")).contains("status"));
        assertTrue(String.valueOf(afterMerge.get("attrs")).contains("open"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void parseHelpers(H2PlatformTables.Mode mode) {
        assertTrue(HybridPromoteColumnMigrator.isHybridPromoteAdd(
                "ALTER TABLE entity_record ADD COLUMN hp_demo_ticket_status VARCHAR(32)"));
        assertTrue(HybridPromoteColumnMigrator.isHybridPromoteDrop(
                "ALTER TABLE entity_record DROP COLUMN hp_demo_ticket_status"));
        assertEquals(
                "hp_demo_ticket_status",
                HybridPromoteColumnMigrator.parsePromotedColumn(
                                "ALTER TABLE entity_record ADD COLUMN hp_demo_ticket_status VARCHAR(32)")
                        .orElseThrow());
        assertEquals(
                "status",
                HybridPromoteColumnMigrator.fieldSnakeFromColumn("demo-ticket", "hp_demo_ticket_status")
                        .orElseThrow());
    }
}
