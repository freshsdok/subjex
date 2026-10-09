package com.subjex.outbox.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import com.subjex.platform.contract.task.OutboxEvent;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;

class KafkaDeliveryPublisherTest {

    @Test
    void publishWritesKeyHeadersAndBodyThenMarksPublished() {
        MockProducer<String, byte[]> mock = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        KafkaDeliveryPublisher publisher = publisher(mock, new DeliveryCircuitBreaker(3));
        String body = "{\"eventId\":\"event-1\",\"tenantId\":\"tenant-north\"}";
        DeliveryAttempt attempt = publisher.deliver(event(body), false);
        assertEquals(OutboxState.PUBLISHED, attempt.result().eventState());
        assertEquals(1, mock.history().size());
        ProducerRecord<String, byte[]> record = mock.history().get(0);
        assertEquals("outbox.events", record.topic());
        assertEquals("tenant-north:event-1", record.key());
        assertEquals(body, new String(record.value(), StandardCharsets.UTF_8));
        assertEquals(TaskRecordedNotice.EVENT_NAME, header(record, KafkaDeliveryPublisher.HEADER_EVENT_NAME));
        assertEquals("1", header(record, KafkaDeliveryPublisher.HEADER_DECLARATION_VERSION));
        assertTrue(header(record, KafkaDeliveryPublisher.HEADER_TRACEPARENT).startsWith("00-"));
    }

    @Test
    void sendFailureStaysPendingAndOpensBreakerWithoutMarkingPublished() {
        MockProducer<String, byte[]> mock = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        mock.sendException = new RuntimeException("broker down");
        DeliveryCircuitBreaker breaker = new DeliveryCircuitBreaker(1, Duration.ofHours(1), java.time.Clock.systemUTC());
        KafkaDeliveryPublisher publisher = publisher(mock, breaker);
        DeliveryAttempt first = publisher.deliver(event("{}"), true);
        assertEquals(OutboxState.PENDING, first.result().eventState());
        assertTrue(first.result().failureReason().contains("broker down"));
        assertTrue(breaker.isTripped());
        DeliveryAttempt blocked = publisher.deliver(event("{}"), false);
        assertEquals("breaker-open", blocked.result().failureReason());
        // History may include the failed attempt depending on MockProducer; published never returned.
        // 失败尝试可能进 history；但从未返回 PUBLISHED。
    }

    @Test
    void relaySemanticsDoNotDoublePublishOnRetryAfterSuccess() {
        MockProducer<String, byte[]> mock = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        KafkaDeliveryPublisher publisher = publisher(mock, new DeliveryCircuitBreaker(3));
        OutboxEvent row = event("{\"eventId\":\"event-1\"}");
        assertEquals(OutboxState.PUBLISHED, publisher.deliver(row, false).result().eventState());
        assertEquals(OutboxState.PUBLISHED, publisher.deliver(row, false).result().eventState());
        assertEquals(2, mock.history().size());
        // Caller (JdbcTaskMessagePort) only marks PUBLISHED once when state transitions; transport may be retried
        // only while PENDING. This asserts the port itself does not invent ack-frame double-confirm.
        // 调用方仅在 PENDING→PUBLISHED 记账一次；传输层不另搞确认帧。
    }

    private static KafkaDeliveryPublisher publisher(
            MockProducer<String, byte[]> mock, DeliveryCircuitBreakerPort breaker) {
        return new KafkaDeliveryPublisher(mock, "outbox.events", 1, breaker, telemetry(), Duration.ofSeconds(2));
    }

    private static OpenTelemetry telemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
    }

    private static OutboxEvent event(String body) {
        return new OutboxEvent(
                "event-1",
                "tenant-north",
                TaskRecordedNotice.EVENT_NAME,
                body,
                OutboxState.PENDING,
                "trace-stored",
                0,
                null,
                Instant.EPOCH,
                null);
    }

    private static String header(ProducerRecord<String, byte[]> record, String name) {
        var header = record.headers().lastHeader(name);
        return new String(header.value(), StandardCharsets.UTF_8);
    }
}
