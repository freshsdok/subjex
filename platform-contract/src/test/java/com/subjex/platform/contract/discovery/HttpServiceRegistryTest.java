package com.subjex.platform.contract.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class HttpServiceRegistryTest {

    @Test
    void twoCallersShareOneRegistryOnARandomPort() throws Exception {
        try (RegistryServer server = RegistryServer.start("operator", "secret")) {
            HttpServiceRegistry first = client(server, "operator", "secret");
            HttpServiceRegistry second = client(server, "operator", "secret");

            first.register(new ServiceEndpoint(PlatformServiceNames.PLATFORM_APP, "127.0.0.1", 8080));
            second.register(new ServiceEndpoint(PlatformServiceNames.SAMPLE_CONSUMER, "127.0.0.1", 8081));

            ServiceEndpoint platform = second.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();
            ServiceEndpoint consumer = first.resolve(PlatformServiceNames.SAMPLE_CONSUMER).orElseThrow();
            assertEquals(8080, platform.port());
            assertEquals(8081, consumer.port());
        }
    }

    @Test
    void statusWordOnTheListDoesNotChangeTheEndpoint() throws Exception {
        try (RegistryServer server = RegistryServer.start("operator", "secret")) {
            server.fixedList("""
                    [{"serviceName":"platform-app","host":"10.0.0.4","port":9090,"status":"unknown"}]
                    """);
            ServiceEndpoint endpoint = client(server, "operator", "secret")
                    .resolve(PlatformServiceNames.PLATFORM_APP)
                    .orElseThrow();
            assertEquals("10.0.0.4", endpoint.host());
            assertEquals(9090, endpoint.port());
        }
    }

    @Test
    void unreachableRegistryIsEmptyAndRegistrationDoesNotThrow() throws Exception {
        int closed = closedPort();
        HttpServiceRegistry registry = new HttpServiceRegistry(
                URI.create("http://127.0.0.1:" + closed), "operator", "secret", Duration.ofMillis(400));

        registry.register(new ServiceEndpoint(PlatformServiceNames.SAMPLE_CONSUMER, "127.0.0.1", 8081));

        assertTrue(registry.resolve(PlatformServiceNames.PLATFORM_APP).isEmpty());
    }

    @Test
    void unreachableRegistryLeavesStaticHostAndPort() throws Exception {
        int closed = closedPort();
        ServiceRegistry registry = new FallbackServiceRegistry(
                new HttpServiceRegistry(
                        URI.create("http://127.0.0.1:" + closed), "operator", "secret", Duration.ofMillis(400)),
                StaticServiceFallback.fromConfig(local(8080), PlatformServiceNames.PLATFORM_APP));

        ServiceEndpoint endpoint = registry.resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();

        assertEquals("127.0.0.1", endpoint.host());
        assertEquals(8080, endpoint.port());
    }

    @Test
    void refusedOperatorIsNotAnEmptyRegistry() throws Exception {
        try (RegistryServer server = RegistryServer.start("operator", "secret")) {
            HttpServiceRegistry registry = client(server, "operator", "wrong");

            assertThrows(IllegalStateException.class, () -> registry.resolve(PlatformServiceNames.PLATFORM_APP));
            assertThrows(
                    IllegalStateException.class,
                    () -> registry.register(new ServiceEndpoint(PlatformServiceNames.SAMPLE_CONSUMER, "127.0.0.1", 8081)));
        }
    }

    private static HttpServiceRegistry client(RegistryServer server, String username, String password) {
        return new HttpServiceRegistry(server.root(), username, password, Duration.ofSeconds(2));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static LocalApplicationConfig local(int port) {
        Map<String, String> properties = Map.of(
                StaticServiceFallback.hostKey(PlatformServiceNames.PLATFORM_APP), "127.0.0.1",
                StaticServiceFallback.portKey(PlatformServiceNames.PLATFORM_APP), Integer.toString(port));
        return new LocalApplicationConfig(properties::get);
    }

    /**
     * In-process HTTP stand-in for platform-app's registry. No Docker.
     * platform-app 登记接口的进程内 HTTP 替身。不用 Docker。
     */
    private static final class RegistryServer implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentHashMap<String, ServiceEndpoint> endpoints = new ConcurrentHashMap<>();
        private volatile String fixedList;

        private RegistryServer(HttpServer server) {
            this.server = server;
        }

        static RegistryServer start(String username, String password) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RegistryServer registry = new RegistryServer(server);
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            server.createContext(HttpServiceRegistry.SERVICES_PATH, exchange -> {
                try {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    if (!expected.equals(authorization)) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        ServiceEndpoint endpoint = ServiceEndpointJson.parseRegistration(body);
                        registry.endpoints.put(endpoint.serviceName(), endpoint);
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    if ("GET".equals(exchange.getRequestMethod())) {
                        byte[] bytes = registry.listJson().getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                        return;
                    }
                    exchange.sendResponseHeaders(405, -1);
                } finally {
                    exchange.close();
                }
            });
            server.start();
            return registry;
        }

        void fixedList(String json) {
            this.fixedList = json;
        }

        URI root() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private String listJson() {
            if (fixedList != null) {
                return fixedList;
            }
            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (ServiceEndpoint endpoint : endpoints.values()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                String registration = ServiceEndpointJson.registration(endpoint);
                json.append(registration, 0, registration.length() - 1).append(",\"status\":\"up\"}");
            }
            json.append(']');
            return json.toString();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
