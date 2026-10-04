package com.subjex.platform.app.trace;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;

/**
 * DiscardingSpanExporter — 丢弃式跨度导出器：没有采集器时，跨度在生成追踪标识之后被丢弃。
 * <p>
 * The SDK still creates trace ids and the W3C header still crosses the process boundary. Nothing is sent to a collector.
 * SDK 仍会生成追踪标识，W3C 头仍会越过进程边界。不会把跨度送给采集器。
 */
public final class DiscardingSpanExporter implements SpanExporter {

    @Override
    public CompletableResultCode export(Collection<SpanData> spans) {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode flush() {
        return CompletableResultCode.ofSuccess();
    }

    @Override
    public CompletableResultCode shutdown() {
        return CompletableResultCode.ofSuccess();
    }
}
