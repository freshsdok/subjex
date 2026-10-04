package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.storage.StoredObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDirectoryObjectStorageTest {

    @TempDir
    Path directory;

    @Test
    void objectIsVisibleOnlyToItsTenant() {
        ObjectStorage storage = new LocalDirectoryObjectStorage(directory);
        byte[] content = "subject-brief".getBytes(StandardCharsets.UTF_8);
        storage.put("tenant-north", "brief", content, "text/plain");

        Optional<StoredObject> found = storage.find("tenant-north", "brief");
        assertTrue(found.isPresent());
        assertArrayEquals(content, found.get().content());
        assertEquals("text/plain", found.get().mediaType());
        assertTrue(storage.find("tenant-other", "brief").isEmpty());
    }
}
