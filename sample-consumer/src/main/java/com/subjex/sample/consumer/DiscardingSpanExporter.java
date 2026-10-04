package com.subjex.sample.consumer;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.util.Collection;

/**
 * DiscardingSpanExporter — 丢弃式跨度导出器：本进程没有采集器时仍能生成追踪标识。
 * <p>
 * The trace id arrives on the traceparent header from platform-app and continues here. Spans are not shipped out.
 * 追踪标识由 platform-app 的 traceparent 头带来，并在这里续上。跨度不会被送出去。
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
