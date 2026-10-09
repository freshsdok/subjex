package com.subjex.platform.app.delivery;

import com.subjex.platform.contract.delivery.DeliveryAttempt;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.delivery.OutboxHmac;
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
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * OutboxSocketPublisher — 出箱套接字投递：{@link DeliveryPort} 的 socket 实现（本机/快速开始演示）。
 * <p>
 * Pushes JDBC outbox rows to sample-consumer over {@code SUBJEX-OUTBOX 2}. Single address, body ≤ 4000 bytes,
 * shared HMAC — not a multi-consumer bus. Optional TLS when an {@link SSLContext} is provided. Failures open
 * only this publisher's {@link DeliveryCircuitBreakerPort}. The current span is written as traceparent.
 * 把 JDBC 出箱行经 {@code SUBJEX-OUTBOX 2} 推给 sample-consumer。单地址、正文 ≤4000 字节、共享 HMAC——
 * 不是多消费方总线。提供 {@link SSLContext} 时走 TLS。失败只打开本实现的熔断端口。当前跨度写成 traceparent。
 */
public final class OutboxSocketPublisher implements DeliveryPort {

    private final String consumerHost;
    private final int consumerPort;
    private final String hmacSecret;
    private final SSLContext sslContext;
    private final DeliveryCircuitBreakerPort breaker;
    private final OpenTelemetry openTelemetry;
    private final Clock clock;

    public OutboxSocketPublisher(
            String consumerHost,
            int consumerPort,
            String hmacSecret,
            SSLContext sslContext,
            DeliveryCircuitBreakerPort breaker,
            OpenTelemetry openTelemetry,
            Clock clock) {
        if (consumerHost == null || consumerHost.isBlank()) {
            throw new IllegalArgumentException("sample consumer host is missing");
        }
        if (consumerPort < 1 || consumerPort > 65535) {
            throw new IllegalArgumentException("sample consumer port is missing");
        }
        OutboxHmac.requireSecret(hmacSecret);
        this.consumerHost = consumerHost;
        this.consumerPort = consumerPort;
        this.hmacSecret = hmacSecret;
        this.sslContext = sslContext;
        this.breaker = Objects.requireNonNull(breaker, "breaker");
        this.openTelemetry = openTelemetry;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Push one stored outbox row — 推送一条已经落库的出箱行。
     *
     * @param failureRequested when true, the sample consumer rejects after taking the notice
     *                         为 true 时，示例消费者收下通知后拒绝
     */
    @Override
    public DeliveryAttempt deliver(OutboxEvent event, boolean failureRequested) {
        if (event == null || event.eventName() == null || event.eventBody() == null) {
            throw new IllegalArgumentException("outbox event is missing");
        }
        Tracer tracer = openTelemetry.getTracer("platform-app");
        Span span = tracer.spanBuilder("deliver-task-recorded").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String traceId = span.getSpanContext().getTraceId();
            if (!breaker.allowCall()) {
                return new DeliveryAttempt(DeliveryResult.pending("breaker-open"), traceId);
            }
            try {
                boolean accepted = push(event, failureRequested);
                if (accepted) {
                    breaker.recordSuccess();
                    return new DeliveryAttempt(DeliveryResult.published(), traceId);
                }
                breaker.recordFailure();
                return new DeliveryAttempt(DeliveryResult.pending("sample-consumer rejected"), traceId);
            } catch (Exception ex) {
                breaker.recordFailure();
                span.recordException(ex);
                return new DeliveryAttempt(DeliveryResult.pending(clip(ex.getMessage())), traceId);
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
        long timestamp = clock.instant().toEpochMilli();
        String signature = OutboxHmac.sign(
                hmacSecret,
                event.eventName(),
                traceparent,
                failureRequested,
                timestamp,
                event.eventBody());
        try (Socket socket = openSocket()) {
            socket.setSoTimeout(3_000);
            OutboxSocketFrame.writeNotice(socket.getOutputStream(), new OutboxSocketFrame.Notice(
                    event.eventName(),
                    traceparent,
                    timestamp,
                    signature,
                    failureRequested,
                    event.eventBody()));
            return OutboxSocketFrame.readAccepted(socket.getInputStream());
        }
    }

    private Socket openSocket() throws IOException {
        if (sslContext == null) {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(consumerHost, consumerPort), 2_000);
            return socket;
        }
        SSLSocket socket = (SSLSocket) sslContext.getSocketFactory().createSocket();
        socket.connect(new InetSocketAddress(consumerHost, consumerPort), 2_000);
        socket.startHandshake();
        return socket;
    }

    private static String clip(String reason) {
        if (reason == null || reason.isBlank()) {
            return "sample-consumer call failed";
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }
}
