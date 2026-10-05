package com.subjex.sample.consumer;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
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
 * TracingConfiguration — 消费者追踪装配：默认丢弃；配置了 OTLP 端点时改为 OTLP/HTTP。
 * <p>
 * The two applications join one trace only through the W3C traceparent on the outbox socket frame.
 * 两个应用只通过出箱套接字帧上的 W3C traceparent 接成同一次追踪。
 */
@Configuration
public class TracingConfiguration {

    @Bean
    OpenTelemetry openTelemetry(
            @Value("${platform.tracing.otlp-endpoint:}") String otlpEndpoint) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter(otlpEndpoint)))
                .build();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
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
