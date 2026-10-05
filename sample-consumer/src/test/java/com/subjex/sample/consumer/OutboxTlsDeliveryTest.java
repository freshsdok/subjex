package com.subjex.sample.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.delivery.OutboxSocketPublisher;
import com.subjex.platform.app.delivery.SamplePathCircuitBreaker;
import com.subjex.platform.app.delivery.SocketDelivery;
import com.subjex.platform.app.trace.DiscardingSpanExporter;
import com.subjex.platform.contract.delivery.OutboxTls;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

/**
 * TLS outbox path with checked-in PKCS12 materials — 用检入的 PKCS12 走 TLS 出箱路径。
 */
class OutboxTlsDeliveryTest {

    private static final String SECRET = "a".repeat(32);
    private static final char[] STORE_PASSWORD = "changeit".toCharArray();

    @Test
    void publisherDeliversOverTls() throws Exception {
        Path keystore = Path.of("src/test/resources/outbox-tls/server.p12");
        Path truststore = Path.of("src/test/resources/outbox-tls/trust.p12");
        SSLContext serverSsl = OutboxTls.serverContext(keystore, STORE_PASSWORD);
        SSLContext clientSsl = OutboxTls.clientContext(truststore, STORE_PASSWORD);
        OpenTelemetry telemetry = telemetry();
        TaskRecordedReceipts receipts = new TaskRecordedReceipts();
        OutboxSocketListener listener = new OutboxSocketListener(
                0, SECRET, serverSsl, telemetry, new ObjectMapper(), receipts, Clock.systemUTC());
        listener.start();
        try {
            OutboxSocketPublisher publisher = new OutboxSocketPublisher(
                    "127.0.0.1",
                    listener.port(),
                    SECRET,
                    clientSsl,
                    new SamplePathCircuitBreaker(3),
                    telemetry,
                    Clock.systemUTC());
            TaskRecordedNotice notice =
                    new TaskRecordedNotice("event-tls-1", "tenant-north", "task-tls-1", "admit-subject");
            OutboxEvent event = new OutboxEvent(
                    notice.eventId(),
                    notice.tenantId(),
                    TaskRecordedNotice.EVENT_NAME,
                    new ObjectMapper().writeValueAsString(notice),
                    OutboxState.PENDING,
                    "trace-tls",
                    0,
                    null,
                    Instant.EPOCH,
                    null);
            SocketDelivery delivery = publisher.deliver(event, false);
            assertEquals(OutboxState.PUBLISHED, delivery.result().eventState());
            assertEquals(notice, receipts.lastNotice());
        } finally {
            listener.stop();
        }
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
}
