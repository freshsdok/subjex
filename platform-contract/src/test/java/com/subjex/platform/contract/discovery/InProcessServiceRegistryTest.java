package com.subjex.platform.contract.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class InProcessServiceRegistryTest {

    @Test
    void registerThenResolveReturnsTheEndpoint() {
        ServiceRegistry registry = new InProcessServiceRegistry();
        registry.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, "127.0.0.1", 8080));

        ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();

        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8080, endpoint.port());
    }

    @Test
    void unknownServiceNameIsEmpty() {
        ServiceRegistry registry = new InProcessServiceRegistry();

        assertTrue(registry.resolve(PlatformServiceNames.PLATFORM_APP).isEmpty());
        assertTrue(registry.resolve("  ").isEmpty());
    }
}
