package com.subjex.sample.consumer.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.discovery.FallbackServiceRegistry;
import com.subjex.platform.contract.discovery.HttpServiceRegistry;
import com.subjex.platform.contract.discovery.InProcessServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlatformAppResolverTest {

    @Test
    void separateProcessResolvesTheStaticFallback() {
        PlatformAppResolver resolver = new PlatformAppResolver(registry(Map.of(
                StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP), "127.0.0.1",
                StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP), "8080")));

        ServiceEndpoint endpoint = resolver.platformApp();

        assertEquals(PlatformServiceNames.PLATFORM_APP, endpoint.serviceName());
        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8080, endpoint.port());
    }

    @Test
    void sharedRegistryWinsOverStaticConfig() {
        ServiceRegistry registry = registry(Map.of(
                StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP), "127.0.0.1",
                StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP), "8080"));
        registry.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, "10.1.0.4", 8080));

        ServiceEndpoint endpoint = new PlatformAppResolver(registry).platformApp();

        assertEquals("10.1.0.4", endpoint.host());
    }

    @Test
    void unreachableHttpRegistryUsesTheStaticFallback() throws Exception {
        int closed;
        try (ServerSocket socket = new ServerSocket(0)) {
            closed = socket.getLocalPort();
        }
        ServiceRegistry http = new HttpServiceRegistry(
                URI.create("http://127.0.0.1:" + closed), "platform-operator", "change-me", Duration.ofMillis(400));
        ServiceRegistry registry = new FallbackServiceRegistry(
                http,
                StaticServiceFallback.fromConfig(
                        new LocalApplicationConfig(Map.of(
                                StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP), "127.0.0.1",
                                StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP), "8080")::get),
                        PlatformServiceNames.PLATFORM_APP));

        ServiceEndpoint endpoint = new PlatformAppResolver(registry).platformApp();

        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8080, endpoint.port());
    }

    @Test
    void missingRegistryAndMissingStaticConfigFail() {
        ServiceRegistry registry = registry(Map.of());

        assertThrows(IllegalStateException.class, () -> new PlatformAppResolver(registry));
    }

    private static ServiceRegistry registry(Map<String, String> properties) {
        return new FallbackServiceRegistry(
                new InProcessServiceRegistry(),
                StaticServiceFallback.fromConfig(
                        new LocalApplicationConfig(properties::get), PlatformServiceNames.PLATFORM_APP));
    }
}
