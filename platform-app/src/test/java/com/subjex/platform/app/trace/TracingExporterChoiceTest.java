package com.subjex.platform.app.trace;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.junit.jupiter.api.Test;

/**
 * TracingExporterChoiceTest — 追踪导出器选择测试：无端点时丢弃，有端点时用 OTLP/HTTP。
 */
class TracingExporterChoiceTest {

    @Test
    void blankEndpointKeepsDiscardingExporter() {
        SpanExporter exporter = TracingConfiguration.spanExporter("  ");
        assertInstanceOf(DiscardingSpanExporter.class, exporter);
    }

    @Test
    void configuredEndpointUsesOtlpHttp() {
        SpanExporter exporter = TracingConfiguration.spanExporter("http://127.0.0.1:4318");
        assertInstanceOf(OtlpHttpSpanExporter.class, exporter);
        assertTrue(exporter.getClass().getName().contains("Otlp"));
    }
}
