package com.subjex.sample.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.contract.delivery.OutboxSocketFrame;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.context.SmartLifecycle;

/**
 * OutboxSocketListener — 出箱套接字监听者：sample-consumer 接收 TaskRecorded 的产品路径。
 * <p>
 * This application opens the listen socket. platform-app opens the client socket and pushes one outbox row.
 * A requested failure is recorded, then rejected, so the publisher breaker can open. traceparent is read from the frame.
 * 本应用打开监听套接字。platform-app 打开客户端套接字并推送一条出箱行。
 * 被要求失败时先记下，再拒绝，以便发布方熔断器打开。traceparent 从帧里读取。
 */
public final class OutboxSocketListener implements SmartLifecycle {

    private static final TextMapGetter<Map<String, String>> TRACE = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final int listenPort;
    private final String operatorName;
    private final String operatorPassword;
    private final OpenTelemetry openTelemetry;
    private final ObjectMapper objectMapper;
    private final TaskRecordedReceipts receipts;
    private ServerSocket server;
    private Thread acceptThread;
    private volatile boolean running;

    public OutboxSocketListener(
            int listenPort,
            String operatorName,
            String operatorPassword,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            TaskRecordedReceipts receipts) {
        if (listenPort < 0 || listenPort > 65535) {
            throw new IllegalArgumentException("listen port is missing");
        }
        if (operatorName == null || operatorName.isBlank() || operatorPassword == null || operatorPassword.isBlank()) {
            throw new IllegalArgumentException("operator is missing");
        }
        this.listenPort = listenPort;
        this.operatorName = operatorName;
        this.operatorPassword = operatorPassword;
        this.openTelemetry = openTelemetry;
        this.objectMapper = objectMapper;
        this.receipts = receipts;
    }

    /**
     * @return bound port, which may differ from the configured port when that port was 0
     *         已绑定的端口；配置为 0 时这是系统分配的端口
     */
    public int port() {
        ServerSocket current = server;
        if (current == null || !current.isBound()) {
            throw new IllegalStateException("outbox socket is not listening");
        }
        return current.getLocalPort();
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        try {
            ServerSocket opened = new ServerSocket();
            opened.setReuseAddress(true);
            opened.bind(new InetSocketAddress("127.0.0.1", listenPort));
            this.server = opened;
            this.running = true;
            this.acceptThread = new Thread(this::acceptLoop, "outbox-socket-accept");
            this.acceptThread.setDaemon(true);
            this.acceptThread.start();
        } catch (IOException ex) {
            throw new IllegalStateException("outbox socket could not listen", ex);
        }
    }

    @Override
    public synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // closing the listen socket is how the accept loop stops
            }
        }
        if (acceptThread != null) {
            try {
                acceptThread.join(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = server.accept();
                try (socket) {
                    handle(socket);
                }
            } catch (IOException ex) {
                if (running) {
                    continue;
                }
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(3_000);
        OutboxSocketFrame.Notice frame = OutboxSocketFrame.readNotice(socket.getInputStream());
        boolean accepted = false;
        if (authorized(frame) && TaskRecordedNotice.EVENT_NAME.equals(frame.eventName())) {
            try {
                TaskRecordedNotice notice = objectMapper.readValue(frame.eventBody(), TaskRecordedNotice.class);
                accepted = take(notice, frame);
            } catch (JsonProcessingException ex) {
                accepted = false;
            }
        }
        OutboxSocketFrame.writeOutcome(socket.getOutputStream(), accepted);
    }

    private boolean take(TaskRecordedNotice notice, OutboxSocketFrame.Notice frame) {
        Context extracted = openTelemetry.getPropagators().getTextMapPropagator()
                .extract(Context.current(), Map.of("traceparent", frame.traceparent()), TRACE);
        Tracer tracer = openTelemetry.getTracer("sample-consumer");
        Span span = tracer.spanBuilder("receive-task-recorded").setParent(extracted).startSpan();
        try {
            receipts.accept(notice, span.getSpanContext().getTraceId());
            return !frame.failureRequested();
        } finally {
            span.end();
        }
    }

    private boolean authorized(OutboxSocketFrame.Notice frame) {
        return same(operatorName, frame.operatorName()) && same(operatorPassword, frame.operatorPassword());
    }

    private static boolean same(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }
}
