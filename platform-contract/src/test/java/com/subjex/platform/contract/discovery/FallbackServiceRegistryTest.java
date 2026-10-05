package com.subjex.platform.contract.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.subjex.platform.contract.config.LocalApplicationConfig;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FallbackServiceRegistryTest {

    @Test
    void emptyRegistryUsesStaticConfig() {
        ServiceRegistry registry = registryWithStaticPort(8080);

        ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();

        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8080, endpoint.port());
    }

    @Test
    void registeredEndpointWinsOverStaticConfig() {
        ServiceRegistry registry = registryWithStaticPort(8080);
        registry.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, "10.0.0.8", 9090));

        ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();

        assertEquals("10.0.0.8", endpoint.host());
        assertEquals(9090, endpoint.port());
    }

    @Test
    void staticConfigRefusesRegistration() {
        ServiceRegistry fallback = StaticServiceFallback.fromConfig(local(8080), PlatformServiceNames.PLATFORM_APP);

        assertThrows(
                UnsupportedOperationException.class,
                () -> fallback.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, "127.0.0.1", 8080)));
    }

    private static ServiceRegistry registryWithStaticPort(int port) {
        return new FallbackServiceRegistry(
                new InProcessServiceRegistry(),
                StaticServiceFallback.fromConfig(local(port), PlatformServiceNames.PLATFORM_APP));
    }

    private static LocalApplicationConfig local(int port) {
        Map<String, String> properties = Map.of(
                StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP), "127.0.0.1",
                StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP), Integer.toString(port));
        return new LocalApplicationConfig(properties::get);
    }
}
