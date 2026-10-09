package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.trace.DiscardingSpanExporter;
import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.OutboxSocketFrame;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import com.subjex.platform.contract.task.OutboxEvent;
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
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OutboxSocketPublisherTest {

    @Test
    void failureTripsTheBreakerAndStopsFurtherCalls() throws Exception {
        try (Peer peer = new Peer(false)) {
            OutboxSocketPublisher publisher = publisher(peer.port());
            OutboxEvent event = event("{\"eventId\":\"event-1\"}");
            assertEquals(OutboxState.PENDING, publisher.deliver(event, false).result().eventState());
            assertEquals(OutboxState.PENDING, publisher.deliver(event, false).result().eventState());
            DeliveryAttempt third = publisher.deliver(event, false);
            assertEquals(OutboxState.PENDING, third.result().eventState());
            assertTrue(third.result().failureReason().contains("rejected"));
            assertEquals(3, peer.received.size());

            DeliveryAttempt blocked = publisher.deliver(event, false);
            assertEquals("breaker-open", blocked.result().failureReason());
            assertEquals(3, peer.received.size());
        }
    }

    @Test
    void traceParentCarriesTheSpanAndTheOutboxBody() throws Exception {
        String body = "{\"eventId\":\"event-1\",\"tenantId\":\"tenant-north\"}";
        try (Peer peer = new Peer(true)) {
            OutboxSocketPublisher publisher = publisher(peer.port());
            DeliveryAttempt delivery = publisher.deliver(event(body), false);
            assertEquals(OutboxState.PUBLISHED, delivery.result().eventState());
            OutboxSocketFrame.Notice notice = peer.received.poll(3, TimeUnit.SECONDS);
            assertNotNull(notice);
            assertEquals(body, notice.eventBody());
            assertEquals(TaskRecordedNotice.EVENT_NAME, notice.eventName());
            assertTrue(notice.traceparent().startsWith("00-"));
            assertEquals(delivery.traceId(), notice.traceparent().split("-")[1]);
            assertFalse(delivery.traceId().matches("0+"));
        }
    }

    private static OutboxSocketPublisher publisher(int port) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(new DiscardingSpanExporter()))
                .build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        return new OutboxSocketPublisher(
                "127.0.0.1",
                port,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null,
                new DeliveryCircuitBreaker(3),
                telemetry,
                java.time.Clock.systemUTC());
    }

    private static OutboxEvent event(String body) {
        return new OutboxEvent(
                "event-1",
                "tenant-north",
                TaskRecordedNotice.EVENT_NAME,
                body,
                OutboxState.PENDING,
                "trace-stored-on-the-row",
                0,
                null,
                Instant.EPOCH,
                null);
    }

    /**
     * Peer — 测试对端：在本机打开监听套接字，按出箱帧应答。
     */
    private static final class Peer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread thread;
        private final boolean accepted;
        private final BlockingQueue<OutboxSocketFrame.Notice> received = new LinkedBlockingQueue<>();

        private Peer(boolean accepted) throws IOException {
            this.accepted = accepted;
            this.server = new ServerSocket();
            this.server.bind(new InetSocketAddress("127.0.0.1", 0));
            this.thread = new Thread(this::serve, "outbox-socket-peer");
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
                        OutboxSocketFrame.Notice notice = OutboxSocketFrame.readNotice(socket.getInputStream());
                        received.add(notice);
                        OutboxSocketFrame.writeOutcome(socket.getOutputStream(), accepted);
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
