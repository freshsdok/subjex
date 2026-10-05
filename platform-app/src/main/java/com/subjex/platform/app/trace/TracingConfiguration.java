package com.subjex.platform.app.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TracingConfiguration — 追踪装配：默认丢弃导出器；配置了 OTLP 端点时改为 OTLP/HTTP。
 * <p>
 * platform-app and sample-consumer each build a local SDK. They share a trace by traceparent on the outbox socket.
 * When {@code platform.tracing.otlp-endpoint} / {@code PLATFORM_OTLP_ENDPOINT} / {@code OTEL_EXPORTER_OTLP_ENDPOINT}
 * is set, spans are sent there; otherwise they are discarded after ids are created.
 * platform-app 与 sample-consumer 各自建立本地 SDK，靠出箱套接字上的 traceparent 共享追踪。
 * 配置了 OTLP 端点时把跨度送过去；否则在生成标识后丢弃。
 */
@Configuration
public class TracingConfiguration {

    @Bean
    OpenTelemetry openTelemetry(@Value("${platform.tracing.otlp-endpoint:}") String otlpEndpoint) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter(otlpEndpoint)))
                .build();
        TextMapPropagator propagator = W3CTraceContextPropagator.getInstance();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(propagator))
                .build();
    }

    static SpanExporter spanExporter(String otlpEndpoint) {
        String endpoint = firstNonBlank(
                otlpEndpoint, System.getenv("PLATFORM_OTLP_ENDPOINT"), System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT"));
        if (endpoint == null) {
            return new DiscardingSpanExporter();
        }
        if (!endpoint.toLowerCase(Locale.ROOT).endsWith("/v1/traces")) {
            endpoint = endpoint.replaceAll("/+$", "") + "/v1/traces";
        }
        return OtlpHttpSpanExporter.builder().setEndpoint(endpoint).build();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
