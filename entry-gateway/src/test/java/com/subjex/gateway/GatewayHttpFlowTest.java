package com.subjex.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * GatewayHttpFlowTest — 网关 HTTP 流程测试：转发上游正文与状态码；限流超限回 429 且不再打上游。
 */
@SpringBootTest(properties = {
    "gateway.rate-limit.permits=2",
    "gateway.rate-limit.window-seconds=60",
    "gateway.upstream.base-url=http://127.0.0.1:9"
})
@AutoConfigureMockMvc
class GatewayHttpFlowTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UpstreamSettings upstreamSettings;

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile String lastAuthorization = "";

    @BeforeEach
    void startUpstream() throws IOException {
        hits.set(0);
        lastAuthorization = "";
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] body = "{\"loginName\":\"from-upstream\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Upstream", "yes");
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        upstreamSettings.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stopUpstream() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void forwardsStatusBodyAndAuthorization() throws Exception {
        mockMvc.perform(get("/api/v1/me").header("Authorization", "Basic dGVzdA=="))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Upstream", "yes"))
                .andExpect(content().string("{\"loginName\":\"from-upstream\"}"));
        assertEquals(1, hits.get());
        assertEquals("Basic dGVzdA==", lastAuthorization);
    }

    @Test
    void rateLimitReturns429WithoutCallingUpstreamAgain() throws Exception {
        mockMvc.perform(get("/hello").with(request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        })).andExpect(status().isOk());
        mockMvc.perform(get("/hello").with(request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        })).andExpect(status().isOk());
        int before = hits.get();
        mockMvc.perform(get("/hello").with(request -> {
            request.setRemoteAddr("198.51.100.7");
            return request;
        })).andExpect(status().isTooManyRequests())
                .andExpect(content().string("{\"reason\":\"rate-limited\"}"));
        assertEquals(before, hits.get());
    }

    @Test
    void actuatorStaysLocal() throws Exception {
        int before = hits.get();
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());
        assertEquals(before, hits.get());
    }
}
