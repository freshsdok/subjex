package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.delivery.OutboxSocketFrame;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.OutboxEvent;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OutboxSocketPublisher — 出箱套接字发布者：把 JDBC 出箱行推到 sample-consumer 的产品路径。
 * <p>
 * The bytes on the socket are {@link OutboxEvent#eventBody()}. platform-app opens the client socket and
 * sample-consumer opens the listen socket. Failure on this path opens {@link SamplePathCircuitBreaker}.
 * The current span is written as traceparent. This is not a call inside one process.
 * 套接字上的字节是 {@link OutboxEvent#eventBody()}。platform-app 打开客户端套接字，
 * sample-consumer 打开监听套接字。这条路径失败会打开 {@link SamplePathCircuitBreaker}。
 * 当前跨度写成 traceparent。这不是同一个进程里的调用。
 */
public final class OutboxSocketPublisher {

    private final String consumerHost;
    private final int consumerPort;
    private final String operatorName;
    private final String operatorPassword;
    private final SamplePathCircuitBreaker breaker;
    private final OpenTelemetry openTelemetry;

    public OutboxSocketPublisher(
            String consumerHost,
            int consumerPort,
            String operatorName,
            String operatorPassword,
            SamplePathCircuitBreaker breaker,
            OpenTelemetry openTelemetry) {
        if (consumerHost == null || consumerHost.isBlank()) {
            throw new IllegalArgumentException("sample consumer host is missing");
        }
        if (consumerPort < 1 || consumerPort > 65535) {
            throw new IllegalArgumentException("sample consumer port is missing");
        }
        if (operatorName == null || operatorName.isBlank() || operatorPassword == null || operatorPassword.isBlank()) {
            throw new IllegalArgumentException("sample consumer operator is missing");
        }
        this.consumerHost = consumerHost;
        this.consumerPort = consumerPort;
        this.operatorName = operatorName;
        this.operatorPassword = operatorPassword;
        this.breaker = breaker;
        this.openTelemetry = openTelemetry;
    }

    /**
     * Push one stored outbox row — 推送一条已经落库的出箱行。
     *
     * @param failureRequested when true, the sample consumer rejects after taking the notice
     *                         为 true 时，示例消费者收下通知后拒绝
     */
    public SocketDelivery deliver(OutboxEvent event, boolean failureRequested) {
        if (event == null || event.eventName() == null || event.eventBody() == null) {
            throw new IllegalArgumentException("outbox event is missing");
        }
        Tracer tracer = openTelemetry.getTracer("platform-app");
        Span span = tracer.spanBuilder("deliver-task-recorded").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String traceId = span.getSpanContext().getTraceId();
            if (!breaker.allowCall()) {
                return new SocketDelivery(DeliveryResult.pending("breaker-open"), traceId);
            }
            try {
                boolean accepted = push(event, failureRequested);
                if (accepted) {
                    breaker.recordSuccess();
                    return new SocketDelivery(DeliveryResult.published(), traceId);
                }
                breaker.recordFailure();
                return new SocketDelivery(DeliveryResult.pending("sample-consumer rejected"), traceId);
            } catch (Exception ex) {
                breaker.recordFailure();
                span.recordException(ex);
                return new SocketDelivery(DeliveryResult.pending(clip(ex.getMessage())), traceId);
            }
        } finally {
            span.end();
        }
    }

    private boolean push(OutboxEvent event, boolean failureRequested) throws IOException {
        Map<String, String> carrier = new LinkedHashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, Map::put);
        String traceparent = carrier.get("traceparent");
        if (traceparent == null || traceparent.isBlank()) {
            throw new IOException("traceparent was not written");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(consumerHost, consumerPort), 2_000);
            socket.setSoTimeout(3_000);
            OutboxSocketFrame.writeNotice(socket.getOutputStream(), new OutboxSocketFrame.Notice(
                    event.eventName(),
                    traceparent,
                    operatorName,
                    operatorPassword,
                    failureRequested,
                    event.eventBody()));
            return OutboxSocketFrame.readAccepted(socket.getInputStream());
        }
    }

    private static String clip(String reason) {
        if (reason == null || reason.isBlank()) {
            return "sample-consumer call failed";
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }
}
