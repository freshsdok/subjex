package com.subjex.outbox.kafka;

import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.OutboxEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.internals.RecordHeader;

/**
 * KafkaDeliveryPublisher — Kafka 出箱投递：{@link DeliveryPort} 的可选总线适配器。
 * <p>
 * Same PENDING outbox row → fixed topic. Message key is {@code tenantId:eventId}. Record value is the existing
 * {@link OutboxEvent#eventBody()}; headers carry {@code traceparent}, declaration version, and event name.
 * Broker owns consumer offsets — no ack frame. Failures stay PENDING for existing relay; success then marks delivered.
 * {@code failureRequested} is ignored (socket-demo hook only).
 * 同一 PENDING 行发到固定 topic；键为租户:事件 id；正文沿用 event_body；头带 traceparent、声明版本、事件名。
 * 位移由 broker 管。失败保持 PENDING；成功后再标已投递。忽略 failureRequested。
 */
public final class KafkaDeliveryPublisher implements DeliveryPort {

    public static final String HEADER_TRACEPARENT = "traceparent";
    public static final String HEADER_EVENT_NAME = "event-name";
    public static final String HEADER_DECLARATION_VERSION = "declaration-version";

    private final Producer<String, byte[]> producer;
    private final String topic;
    private final int declarationVersion;
    private final DeliveryCircuitBreakerPort breaker;
    private final OpenTelemetry openTelemetry;
    private final Duration sendTimeout;

    public KafkaDeliveryPublisher(
            Producer<String, byte[]> producer,
            String topic,
            int declarationVersion,
            DeliveryCircuitBreakerPort breaker,
            OpenTelemetry openTelemetry) {
        this(producer, topic, declarationVersion, breaker, openTelemetry, Duration.ofSeconds(10));
    }

    public KafkaDeliveryPublisher(
            Producer<String, byte[]> producer,
            String topic,
            int declarationVersion,
            DeliveryCircuitBreakerPort breaker,
            OpenTelemetry openTelemetry,
            Duration sendTimeout) {
        this.producer = Objects.requireNonNull(producer, "producer");
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("platform.delivery.kafka.topic is missing");
        }
        if (declarationVersion < 1) {
            throw new IllegalArgumentException("declaration version must be at least 1");
        }
        this.topic = topic;
        this.declarationVersion = declarationVersion;
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.openTelemetry = Objects.requireNonNull(openTelemetry, "openTelemetry");
        if (sendTimeout == null || sendTimeout.isNegative() || sendTimeout.isZero()) {
            throw new IllegalArgumentException("send timeout must be positive");
        }
        this.sendTimeout = sendTimeout;
    }

    @Override
    public DeliveryAttempt deliver(OutboxEvent event, boolean failureRequested) {
        if (event == null || event.eventName() == null || event.eventBody() == null) {
            throw new IllegalArgumentException("outbox event is missing");
        }
        if (event.tenantId() == null || event.tenantId().isBlank()
                || event.eventId() == null || event.eventId().isBlank()) {
            throw new IllegalArgumentException("outbox tenantId and eventId are required for the Kafka key");
        }
        Tracer tracer = openTelemetry.getTracer("subjex-outbox-kafka");
        Span span = tracer.spanBuilder("deliver-outbox-kafka").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String traceId = span.getSpanContext().getTraceId();
            if (!breaker.allowCall()) {
                return new DeliveryAttempt(DeliveryResult.pending("breaker-open"), traceId);
            }
            try {
                push(event);
                breaker.recordSuccess();
                return new DeliveryAttempt(DeliveryResult.published(), traceId);
            } catch (Exception ex) {
                breaker.recordFailure();
                span.recordException(ex);
                return new DeliveryAttempt(DeliveryResult.pending(clip(ex.getMessage())), traceId);
            }
        } finally {
            span.end();
        }
    }

    private void push(OutboxEvent event) throws Exception {
        Map<String, String> carrier = new LinkedHashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, Map::put);
        String traceparent = carrier.get("traceparent");
        if (traceparent == null || traceparent.isBlank()) {
            // Noop OpenTelemetry may omit W3C injection; synthesize from the active span when possible.
            // 无传播器时尽量用当前跨度拼出 traceparent。
            var ctx = Span.current().getSpanContext();
            if (ctx.isValid()) {
                traceparent = "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-"
                        + (ctx.getTraceFlags().isSampled() ? "01" : "00");
            } else {
                throw new IllegalStateException("traceparent was not written");
            }
        }
        String key = event.tenantId() + ":" + event.eventId();
        byte[] body = event.eventBody().getBytes(StandardCharsets.UTF_8);
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, key, body);
        record.headers().add(new RecordHeader(HEADER_TRACEPARENT, traceparent.getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(HEADER_EVENT_NAME, event.eventName().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader(
                HEADER_DECLARATION_VERSION,
                Integer.toString(declarationVersion).getBytes(StandardCharsets.UTF_8)));
        Future<RecordMetadata> future = producer.send(record);
        future.get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static String clip(String reason) {
        if (reason == null || reason.isBlank()) {
            return "kafka send failed";
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }
}
