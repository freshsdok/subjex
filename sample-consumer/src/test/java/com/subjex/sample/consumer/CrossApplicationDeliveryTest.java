package com.subjex.sample.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.delivery.OutboxSocketPublisher;
import com.subjex.platform.app.delivery.SamplePathCircuitBreaker;
import com.subjex.platform.app.delivery.SocketDelivery;
import com.subjex.platform.app.trace.DiscardingSpanExporter;
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
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The outbox row crosses the two applications — 出箱行穿过两个应用。
 * <p>
 * sample-consumer is a real Spring application and opens the listen socket.
 * platform-app's {@link OutboxSocketPublisher} opens the client socket in this same JVM.
 * The payload is {@link OutboxEvent#eventBody()}, the text stored on the outbox row.
 * They share a trace id through traceparent. No collector and no database are started here.
 * sample-consumer 是一个真实的 Spring 应用，并打开监听套接字。
 * platform-app 的 {@link OutboxSocketPublisher} 在同一个 JVM 里打开客户端套接字。
 * 载荷是 {@link OutboxEvent#eventBody()}，也就是出箱行上的文本。
 * 它们通过 traceparent 共享追踪标识。这里不启动采集器，也不启动数据库。
 */
@SpringBootTest(
        classes = SampleConsumerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "platform.delivery.listen-port=0",
            "platform.delivery.hmac-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "platform.delivery.allow-insecure=true",
            // platform-app is a test dependency, so its JDBC starter is visible here.
            // The consumer process does not own the platform tables.
            // platform-app 是测试依赖，因此这里能看见它的 JDBC 启动器。
            // 消费者进程不拥有平台表。
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration"
        })
class CrossApplicationDeliveryTest {

    @Autowired
    private OutboxSocketListener listener;

    @Autowired
    private TaskRecordedReceipts receipts;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void consumerReceivesTheOutboxBodyOnTheSameTrace() throws Exception {
        OutboxSocketPublisher publisher = publisher(new SamplePathCircuitBreaker(3));
        TaskRecordedNotice notice = new TaskRecordedNotice(
                "event-cross-1", "tenant-north", "task-cross-1", "admit-subject");
        SocketDelivery delivery = publisher.deliver(event(notice), false);

        assertEquals(OutboxState.PUBLISHED, delivery.result().eventState());
        assertEquals(notice, receipts.lastNotice());
        assertEquals(delivery.traceId(), receipts.lastTraceId());
        assertNotNull(receipts.lastTraceId());
        assertFalse(receipts.lastTraceId().matches("0+"));
    }

    @Test
    void consumerFailureTripsThePublisherBreaker() throws Exception {
        OutboxSocketPublisher publisher = publisher(new SamplePathCircuitBreaker(3));
        TaskRecordedNotice notice = new TaskRecordedNotice(
                "event-cross-2", "tenant-north", "task-cross-2", "admit-subject");
        OutboxEvent event = event(notice);
        int before = receipts.count();
        publisher.deliver(event, true);
        publisher.deliver(event, true);
        publisher.deliver(event, true);
        assertEquals(before + 3, receipts.count());

        SocketDelivery blocked = publisher.deliver(event, true);
        assertEquals("breaker-open", blocked.result().failureReason());
        assertEquals(before + 3, receipts.count());
    }

    private OutboxEvent event(TaskRecordedNotice notice) throws Exception {
        return new OutboxEvent(
                notice.eventId(),
                notice.tenantId(),
                TaskRecordedNotice.EVENT_NAME,
                objectMapper.writeValueAsString(notice),
                OutboxState.PENDING,
                "trace-stored-on-the-row",
                0,
                null,
                Instant.EPOCH,
                null);
    }

    private OutboxSocketPublisher publisher(SamplePathCircuitBreaker breaker) {
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
                listener.port(),
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null,
                breaker,
                telemetry,
                java.time.Clock.systemUTC());
    }
}
