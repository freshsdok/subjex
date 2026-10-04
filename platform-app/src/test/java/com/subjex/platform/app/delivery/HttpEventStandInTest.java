package com.subjex.platform.app.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.sun.net.httpserver.HttpServer;

class HttpEventStandInTest {

    @Test
    void failureTripsTheBreakerAndStopsFurtherCalls() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inbox/task-recorded", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpEventStandIn standIn = standIn(server);
            TaskRecordedNotice notice = notice();
            assertEquals(OutboxState.PENDING, standIn.deliver(notice, false).result().eventState());
            assertEquals(OutboxState.PENDING, standIn.deliver(notice, false).result().eventState());
            StandInDelivery third = standIn.deliver(notice, false);
            assertEquals(OutboxState.PENDING, third.result().eventState());
            assertTrue(third.result().failureReason().contains("500"));
            assertEquals(3, hits.get());

            StandInDelivery blocked = standIn.deliver(notice, false);
            assertEquals("breaker-open", blocked.result().failureReason());
            assertEquals(3, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void traceParentHeaderCarriesTheSpanTraceId() throws Exception {
        AtomicReference<String> traceparent = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/inbox/task-recorded", exchange -> {
            traceparent.set(exchange.getRequestHeaders().getFirst("traceparent"));
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try {
            HttpEventStandIn standIn = standIn(server);
            StandInDelivery delivery = standIn.deliver(notice(), false);
            assertEquals(OutboxState.PUBLISHED, delivery.result().eventState());
            String header = traceparent.get();
            assertTrue(header.startsWith("00-"));
            assertEquals(delivery.traceId(), header.split("-")[1]);
            assertFalse(delivery.traceId().matches("0+"));
        } finally {
            server.stop(0);
        }
    }

    private static HttpEventStandIn standIn(HttpServer server) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(new DiscardingSpanExporter()))
                .build();
        OpenTelemetry telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        int port = server.getAddress().getPort();
        return new HttpEventStandIn(
                "http://127.0.0.1:" + port,
                "platform-operator",
                "change-me",
                new SamplePathCircuitBreaker(3),
                telemetry,
                new ObjectMapper());
    }

    private static TaskRecordedNotice notice() {
        return new TaskRecordedNotice("event-1", "tenant-north", "task-1", "admit-subject");
    }
}
