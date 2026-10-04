package com.subjex.sample.consumer;

import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * CrossProcessEventSubscriber — 跨进程事件订阅者。
 * <p>
 * v1 stand-in: the subscription is HTTP {@code POST /inbox/task-recorded}. The event is {@link TaskRecordedNotice},
 * published by platform-app and received by this application. A forced failure is the sample-path fault that trips the breaker.
 * 第一版替身：订阅方式是 HTTP {@code POST /inbox/task-recorded}。事件是 {@link TaskRecordedNotice}，
 * 由 platform-app 发布、由本应用接收。被要求失败是示例路径上的故障，用来触发熔断。
 */
@RestController
public class CrossProcessEventSubscriber {

    public static final String FAIL_HEADER = "X-Sample-Fail";

    private static final TextMapGetter<HttpServletRequest> HEADER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return java.util.Collections.list(carrier.getHeaderNames());
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            return carrier == null ? null : carrier.getHeader(key);
        }
    };

    private final OpenTelemetry openTelemetry;
    private final TaskRecordedReceipts receipts;

    public CrossProcessEventSubscriber(OpenTelemetry openTelemetry, TaskRecordedReceipts receipts) {
        this.openTelemetry = openTelemetry;
        this.receipts = receipts;
    }

    @PostMapping("/inbox/task-recorded")
    public ResponseEntity<Void> receive(@RequestBody TaskRecordedNotice notice, HttpServletRequest request) {
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), request, HEADER);
        Tracer tracer = openTelemetry.getTracer("sample-consumer");
        Span span = tracer.spanBuilder("receive-task-recorded").setParent(extracted).startSpan();
        try {
            String traceId = span.getSpanContext().getTraceId();
            receipts.accept(notice, traceId);
            if ("true".equalsIgnoreCase(request.getHeader(FAIL_HEADER))) {
                return ResponseEntity.status(500).header("X-Trace-Id", traceId).build();
            }
            return ResponseEntity.noContent().header("X-Trace-Id", traceId).build();
        } finally {
            span.end();
        }
    }
}
