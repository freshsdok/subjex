package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.trace.DiscardingSpanExporter;
import com.subjex.platform.contract.delivery.OutboxSocketFrame;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


/**
 * OutboxRelayTest — purpose: poll PENDING outbox and push via DeliveryPort.
 * Gates: success marks published; failure keeps PENDING for retry; breaker open skips (fail-closed).
 * <p>
 * 目的：拉取 PENDING 出箱经 DeliveryPort 推送。门禁：成功已发布；失败仍 PENDING；熔断打开则跳过。
 */
class OutboxRelayTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void relayPublishesPendingRowsAndDeadLettersAfterMaxAttempts(H2PlatformTables.Mode mode) throws Exception {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Clock clock = Clock.systemUTC();
        insertPending(jdbc, "event-relay-1", "{\"eventId\":\"event-relay-1\"}");

        try (Peer peer = new Peer(true)) {
            JdbcTaskMessagePort port = port(jdbc, source, peer.port(), new DeliveryCircuitBreaker(3), clock);
            assertEquals(1, port.relayPending(10));
            assertEquals(OutboxState.PUBLISHED.name(), stateOf(jdbc, "event-relay-1"));
        }

        insertPending(jdbc, "event-relay-dead", "{\"eventId\":\"event-relay-dead\"}");
        try (Peer peer = new Peer(false)) {
            // Cooldown long enough that half-open does not interfere within this test.
            // 冷却足够长，本测试内半开不会插手。
            DeliveryCircuitBreaker breaker =
                    new DeliveryCircuitBreaker(99, Duration.ofHours(1), clock);
            JdbcTaskMessagePort port = port(jdbc, source, peer.port(), breaker, clock);
            assertEquals(0, port.relayPending(10));
            assertEquals(0, port.relayPending(10));
            assertEquals(0, port.relayPending(10));
            assertEquals(OutboxState.DEAD.name(), stateOf(jdbc, "event-relay-dead"));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dead_letter WHERE origin_id = ?", Long.class, "event-relay-dead"));
        }
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void relayDoesNotBurnAttemptsWhileBreakerOpen(H2PlatformTables.Mode mode) throws Exception {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Clock clock = Clock.systemUTC();
        insertPending(jdbc, "event-held", "{\"eventId\":\"event-held\"}");

        DeliveryCircuitBreaker breaker = new DeliveryCircuitBreaker(1, Duration.ofHours(1), clock);
        breaker.recordFailure();
        assertTrue(breaker.isOpen());

        try (Peer peer = new Peer(true)) {
            JdbcTaskMessagePort port = port(jdbc, source, peer.port(), breaker, clock);
            assertEquals(0, port.relayPending(10));
            assertEquals(0, port.relayPending(10));
            assertEquals(OutboxState.PENDING.name(), stateOf(jdbc, "event-held"));
            assertEquals(0, jdbc.queryForObject(
                    "SELECT attempt_count FROM outbox_event WHERE event_id = ?", Integer.class, "event-held"));
            assertEquals(0, peer.accepted.get());
        }
    }

    private static JdbcTaskMessagePort port(
            JdbcTemplate jdbc, DataSource source, int port, DeliveryCircuitBreakerPort breaker, Clock clock) {
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        OpenTelemetry telemetry = telemetry();
        OutboxSocketPublisher publisher = new OutboxSocketPublisher(
                "127.0.0.1", port, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", null, breaker, telemetry, clock);
        return new JdbcTaskMessagePort(
                jdbc,
                tx,
                new JdbcIdempotencyPort(jdbc, clock),
                new JdbcAuditPort(jdbc),
                new SingleProcessLock(clock),
                publisher,
                telemetry,
                new ObjectMapper(),
                clock);
    }

    private static OpenTelemetry telemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(new DiscardingSpanExporter()))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    private static void insertPending(JdbcTemplate jdbc, String eventId, String body) {
        jdbc.update(
                """
                INSERT INTO outbox_event (
                    event_id, tenant_id, event_name, event_body, event_state, trace_id,
                    attempt_count, failure_reason, occurred_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                "tenant-north",
                TaskRecordedNotice.EVENT_NAME,
                body,
                OutboxState.PENDING.name(),
                "trace-relay",
                0,
                null,
                Timestamp.from(Instant.parse("2026-10-05T00:00:00Z")),
                null);
    }

    private static String stateOf(JdbcTemplate jdbc, String eventId) {
        return jdbc.queryForObject(
                "SELECT event_state FROM outbox_event WHERE event_id = ?", String.class, eventId);
    }

    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final boolean acceptOutcome;
        private final AtomicInteger accepted = new AtomicInteger();

        private Peer(boolean accepted) throws IOException {
            this.acceptOutcome = accepted;
            this.server = new ServerSocket();
            this.server.bind(new InetSocketAddress("127.0.0.1", 0));
            this.thread = new Thread(this::serve, "outbox-relay-peer");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        private int port() {
            return server.getLocalPort();
        }

        private void serve() {
            while (!server.isClosed()) {
                try {
                    Socket socket = server.accept();
                    try (socket) {
                        socket.setSoTimeout(3_000);
                        OutboxSocketFrame.readNotice(socket.getInputStream());
                        OutboxSocketFrame.writeOutcome(socket.getOutputStream(), acceptOutcome);
                        if (acceptOutcome) {
                            accepted.incrementAndGet();
                        }
                    }
                } catch (IOException ex) {
                    if (!server.isClosed()) {
                        continue;
                    }
                }
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
