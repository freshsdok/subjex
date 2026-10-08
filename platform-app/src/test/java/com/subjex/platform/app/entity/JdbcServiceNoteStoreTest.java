package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.generated.ServiceNote;
import com.subjex.platform.app.security.H2PlatformTables;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcServiceNoteStoreTest — JDBC 服务备注：Flyway V11 表可保存、按 id 查找、列表稳定排序，upsert 覆盖同 id。
 */
class JdbcServiceNoteStoreTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void savesFindsListsAndUpserts(H2PlatformTables.Mode mode) {
        JdbcServiceNoteStore store = new JdbcServiceNoteStore(new JdbcTemplate(H2PlatformTables.migrated(mode)));
        assertTrue(store.list(10).isEmpty());

        store.save(new ServiceNote("n-b", "Beta", "body-b", 2));
        store.save(new ServiceNote("n-a", "Alpha", null, null));
        assertEquals(2, store.list(10).size());
        assertEquals("n-a", store.list(10).get(0).noteId());
        assertEquals("n-b", store.list(10).get(1).noteId());
        assertTrue(store.findById("n-a").isPresent());
        assertEquals("Alpha", store.findById("n-a").orElseThrow().title());

        store.save(new ServiceNote("n-a", "Alpha-2", "updated", 9));
        ServiceNote again = store.findById("n-a").orElseThrow();
        assertEquals("Alpha-2", again.title());
        assertEquals("updated", again.body());
        assertEquals(9, again.priority());
        assertEquals(2, store.list(10).size());
    }

    @Test
    void emptyTableListsNothing() {
        JdbcServiceNoteStore store =
                new JdbcServiceNoteStore(new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL)));
        List<ServiceNote> listed = store.list(100);
        assertTrue(listed.isEmpty());
    }
}
