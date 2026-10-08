package com.subjex.platform.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.entity.generated.ServiceNote;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * InMemoryServiceNoteStoreTest — 内存实体桩：保存后可按 id 找到，列表最新在前。
 */
class InMemoryServiceNoteStoreTest {

    @Test
    void savesFindsAndListsNewestFirst() {
        InMemoryServiceNoteStore store = new InMemoryServiceNoteStore();
        assertEquals(1, store.list(10).size());
        store.save(new ServiceNote("n-2", "Second", "body", 2));
        assertTrue(store.findById("n-2").isPresent());
        List<ServiceNote> listed = store.list(10);
        assertEquals("n-2", listed.get(0).noteId());
        assertEquals("demo-note-1", listed.get(1).noteId());
    }
}
