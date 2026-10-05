package com.subjex.platform.app.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TracingConfiguration — 追踪装配：使用 OpenTelemetry API，并在没有采集器时用丢弃导出器托底。
 * <p>
 * platform-app and sample-consumer each build a local SDK. They share a trace by traceparent on the outbox socket, not by a collector.
 * platform-app 与 sample-consumer 各自建立本地 SDK。它们靠出箱套接字上的 traceparent 共享一次追踪，不靠采集器。
 */
@Configuration
public class TracingConfiguration {

    @Bean
    OpenTelemetry openTelemetry() {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(new DiscardingSpanExporter()))
                .build();
        TextMapPropagator propagator = io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator.getInstance();
        return OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(propagator))
                .build();
    }
}
