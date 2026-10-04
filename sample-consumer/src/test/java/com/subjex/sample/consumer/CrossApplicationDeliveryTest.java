package com.subjex.sample.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.delivery.HttpEventStandIn;
import com.subjex.platform.app.delivery.SamplePathCircuitBreaker;
import com.subjex.platform.app.delivery.StandInDelivery;
import com.subjex.platform.app.trace.DiscardingSpanExporter;
import com.subjex.platform.contract.task.OutboxState;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * The event crosses the two applications — 事件穿过两个应用。
 * <p>
 * sample-consumer is a real Spring application. platform-app's HTTP stand-in is the publisher.
 * They share a trace id through the traceparent header. No collector is required.
 * sample-consumer 是一个真实的 Spring 应用。发布方是 platform-app 的 HTTP 替身。
 * 它们通过 traceparent 头共享追踪标识。不需要采集器。
 */
@SpringBootTest(
        classes = SampleConsumerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // platform-app is a test dependency, so its JDBC starter is visible here.
            // The consumer process does not own the platform tables.
            // platform-app 是测试依赖，因此这里能看见它的 JDBC 启动器。
            // 消费者进程不拥有平台表。
            "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.actuate.autoconfigure.jdbc.DataSourceHealthContributorAutoConfiguration"
        })
class CrossApplicationDeliveryTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TaskRecordedReceipts receipts;

    @Test
    void consumerReceivesTheEventOnTheSameTrace() {
        HttpEventStandIn standIn = standIn(new SamplePathCircuitBreaker(3));
        TaskRecordedNotice notice = new TaskRecordedNotice(
                "event-cross-1", "tenant-north", "task-cross-1", "admit-subject");
        StandInDelivery delivery = standIn.deliver(notice, false);

        assertEquals(OutboxState.PUBLISHED, delivery.result().eventState());
        assertEquals(notice, receipts.lastNotice());
        assertEquals(delivery.traceId(), receipts.lastTraceId());
        assertNotNull(receipts.lastTraceId());
        assertFalse(receipts.lastTraceId().matches("0+"));
    }

    @Test
    void consumerFailureTripsThePublisherBreaker() {
        HttpEventStandIn standIn = standIn(new SamplePathCircuitBreaker(3));
        TaskRecordedNotice notice = new TaskRecordedNotice(
                "event-cross-2", "tenant-north", "task-cross-2", "admit-subject");
        int before = receipts.count();
        standIn.deliver(notice, true);
        standIn.deliver(notice, true);
        standIn.deliver(notice, true);
        assertEquals(before + 3, receipts.count());

        StandInDelivery blocked = standIn.deliver(notice, true);
        assertEquals("breaker-open", blocked.result().failureReason());
        assertEquals(before + 3, receipts.count());
    }

    private HttpEventStandIn standIn(SamplePathCircuitBreaker breaker) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(new DiscardingSpanExporter()))
                .build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        return new HttpEventStandIn(
                "http://127.0.0.1:" + port,
                "platform-operator",
                "change-me",
                breaker,
                telemetry,
                new ObjectMapper());
    }
}
