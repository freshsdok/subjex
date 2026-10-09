package com.subjex.platform.contract.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

class HttpConfigSourceTest {

    @Test
    void twoCallersShareOneNamedOverrideOnARandomPort() throws Exception {
        try (ConfigServer server = ConfigServer.start("operator", "secret")) {
            HttpConfigSource first = client(server, "operator", "secret");
            HttpConfigSource second = client(server, "operator", "secret");

            first.override("platform.demo.message", "from-first");

            ConfigEntry entry = second.read("platform.demo.message").orElseThrow();
            assertEquals("from-first", entry.value());
            assertEquals(ConfigOrigin.OVERRIDE, entry.origin());
            assertEquals("from-first", first.lookup("platform.demo.message").orElseThrow());
        }
    }

    @Test
    void unreachableSourceFallsBackToLocalApplicationConfig() throws Exception {
        int closed = closedPort();
        ConfigSource local = new LocalApplicationConfig(Map.of("platform.demo.message", "local-only")::get);
        ConfigSource layered = new OverridingConfigSource(
                new HttpConfigSource(
                        URI.create("http://127.0.0.1:" + closed), "operator", "secret", Duration.ofMillis(400)),
                local);

        assertEquals("local-only", layered.lookup("platform.demo.message").orElseThrow());
    }

    @Test
    void unreachableOverrideDoesNotThrow() throws Exception {
        int closed = closedPort();
        HttpConfigSource remote = new HttpConfigSource(
                URI.create("http://127.0.0.1:" + closed), "operator", "secret", Duration.ofMillis(400));

        remote.override("platform.demo.message", "ignored");

        assertTrue(remote.lookup("platform.demo.message").isEmpty());
    }

    @Test
    void refusedOperatorIsNotAMissingKey() throws Exception {
        try (ConfigServer server = ConfigServer.start("operator", "secret")) {
            HttpConfigSource remote = client(server, "operator", "wrong");

            assertThrows(IllegalStateException.class, () -> remote.read("platform.demo.message"));
            assertThrows(IllegalStateException.class, () -> remote.override("platform.demo.message", "nope"));
        }
    }

    @Test
    void conditionalReadAndOverrideHonorEtags() throws Exception {
        try (ConfigServer server = ConfigServer.start("operator", "secret")) {
            HttpConfigSource client = client(server, "operator", "secret");
            client.override("platform.demo.message", "v1");
            ConfigEntry first = client.read("platform.demo.message").orElseThrow();
            assertEquals(1L, first.revision());
            assertEquals(Optional.of(1L), client.knownRevision("platform.demo.message"));
            assertTrue(client.readIfNoneMatch("platform.demo.message").isEmpty());
            client.override("platform.demo.message", "v2");
            assertEquals(Optional.of(2L), client.knownRevision("platform.demo.message"));
            ConfigEntry second = client.read("platform.demo.message").orElseThrow();
            assertEquals("v2", second.value());
            assertEquals(2L, second.revision());
        }
    }

    @Test
    void staleIfMatchRejectsOverride() throws Exception {
        try (ConfigServer server = ConfigServer.start("operator", "secret")) {
            HttpConfigSource writer = client(server, "operator", "secret");
            HttpConfigSource stale = client(server, "operator", "secret");
            writer.override("platform.demo.message", "v1");
            stale.read("platform.demo.message");
            writer.override("platform.demo.message", "v2");
            assertThrows(IllegalStateException.class, () -> stale.override("platform.demo.message", "lost"));
            assertEquals("v2", writer.read("platform.demo.message").orElseThrow().value());
        }
    }

    private static HttpConfigSource client(ConfigServer server, String username, String password) {
        return new HttpConfigSource(server.root(), username, password, Duration.ofSeconds(2));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * In-process HTTP stand-in for platform-app's config entries. No Docker.
     * platform-app 配置条目接口的进程内 HTTP 替身。不用 Docker。
     */
    private static final class ConfigServer implements AutoCloseable {
        private final HttpServer server;
        private final ConcurrentHashMap<String, String> overrides = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Long> revisions = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, String> local = new ConcurrentHashMap<>();

        private ConfigServer(HttpServer server) {
            this.server = server;
            local.put("platform.demo.message", "file-default");
        }

        static ConfigServer start(String username, String password) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ConfigServer config = new ConfigServer(server);
            String expected = "Basic " + Base64.getEncoder().encodeToString(
                    (username + ":" + password).getBytes(StandardCharsets.UTF_8));
            server.createContext(HttpConfigSource.ENTRIES_PATH, exchange -> {
                try {
                    String authorization = exchange.getRequestHeaders().getFirst("Authorization");
                    if (!expected.equals(authorization)) {
                        exchange.sendResponseHeaders(401, -1);
                        return;
                    }
                    if ("POST".equals(exchange.getRequestMethod())) {
                        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                        ConfigEntryJson.OverrideRequest request = ConfigEntryJson.parseOverride(body);
                        long current = config.revisions.getOrDefault(request.key(), 0L);
                        String ifMatch = exchange.getRequestHeaders().getFirst(ConfigETags.HEADER_IF_MATCH);
                        if (!ConfigETags.matchAllows(ifMatch, current)) {
                            exchange.sendResponseHeaders(412, -1);
                            return;
                        }
                        long next = current + 1L;
                        config.overrides.put(request.key(), request.value());
                        config.revisions.put(request.key(), next);
                        exchange.getResponseHeaders().set(ConfigETags.HEADER_ETAG, ConfigETags.ofRevision(next));
                        exchange.sendResponseHeaders(204, -1);
                        return;
                    }
                    if ("GET".equals(exchange.getRequestMethod())) {
                        String query = exchange.getRequestURI().getRawQuery();
                        String key = query == null || !query.startsWith("key=")
                                ? ""
                                : java.net.URLDecoder.decode(query.substring(4), StandardCharsets.UTF_8);
                        ConfigListing listing = new ConfigListing(
                                keyVal -> Optional.ofNullable(config.overrides.get(keyVal)),
                                keyVal -> Optional.ofNullable(config.local.get(keyVal)));
                        var entry = listing.entry(key);
                        if (entry.isEmpty()) {
                            exchange.sendResponseHeaders(404, -1);
                            return;
                        }
                        long revision = config.revisions.getOrDefault(key, entry.get().origin() == ConfigOrigin.OVERRIDE ? 1L : 0L);
                        ConfigEntry withRev = new ConfigEntry(
                                entry.get().namespace(),
                                entry.get().key(),
                                entry.get().value(),
                                entry.get().origin(),
                                revision);
                        String etag = ConfigETags.ofRevision(revision);
                        String ifNoneMatch = exchange.getRequestHeaders().getFirst(ConfigETags.HEADER_IF_NONE_MATCH);
                        if (ConfigETags.noneMatchHits(ifNoneMatch, revision)) {
                            exchange.getResponseHeaders().set(ConfigETags.HEADER_ETAG, etag);
                            exchange.sendResponseHeaders(304, -1);
                            return;
                        }
                        byte[] bytes = ConfigEntryJson.document(withRev).getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.getResponseHeaders().set(ConfigETags.HEADER_ETAG, etag);
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
            return config;
        }

        URI root() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
