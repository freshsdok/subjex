package com.subjex.platform.app.storage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.storage.ObjectStorage;
import java.util.ServiceLoader;
import org.junit.jupiter.api.Test;

class ObjectStorageSpiTest {

    @Test
    void compiledImplementationIsRegisteredOnTheSpi() {
        boolean found = ServiceLoader.load(ObjectStorage.class).stream()
                .anyMatch(provider -> provider.type().equals(LocalDirectoryObjectStorage.class));
        assertTrue(found);
    }
}
