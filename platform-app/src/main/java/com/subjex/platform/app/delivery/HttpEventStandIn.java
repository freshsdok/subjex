package com.subjex.platform.app.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.contract.task.DeliveryResult;
import com.subjex.platform.contract.task.TaskRecordedNotice;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HttpEventStandIn — 跨进程事件的 HTTP 替身。
 * <p>
 * v1 stand-in for a broker. {@link TaskRecordedNotice} leaves platform-app and is received by sample-consumer.
 * The call is HTTP between the two applications, not a method call inside one process. Do not add a second messaging port for it.
 * 第一版的消息中间件替身。{@link TaskRecordedNotice} 离开 platform-app，由 sample-consumer 接收。
 * 这是两个应用之间的 HTTP，不是同一个进程里的方法调用。不要为此再加一个消息端口。
 */
public final class HttpEventStandIn {

    private final String consumerBaseUrl;
    private final String username;
    private final String password;
    private final SamplePathCircuitBreaker breaker;
    private final OpenTelemetry openTelemetry;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public HttpEventStandIn(
            String consumerBaseUrl,
            String username,
            String password,
            SamplePathCircuitBreaker breaker,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper) {
        this.consumerBaseUrl = stripTrailingSlash(consumerBaseUrl);
        this.username = username;
        this.password = password;
        this.breaker = breaker;
        this.openTelemetry = openTelemetry;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    /**
     * Deliver one notice across the application boundary — 把一条通知送到应用边界之外。
     *
     * @param forceFailure when true, the sample consumer is asked to fail so this breaker can open
     *                     为 true 时，要求示例消费者失败，以便本熔断器打开
     */
    public StandInDelivery deliver(TaskRecordedNotice notice, boolean forceFailure) {
        Tracer tracer = openTelemetry.getTracer("platform-app");
        Span span = tracer.spanBuilder("deliver-task-recorded").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            String traceId = span.getSpanContext().getTraceId();
            if (!breaker.allowCall()) {
                return new StandInDelivery(DeliveryResult.pending("breaker-open"), traceId);
            }
            try {
                HttpRequest request = build(notice, forceFailure);
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    breaker.recordSuccess();
                    return new StandInDelivery(DeliveryResult.published(), traceId);
                }
                breaker.recordFailure();
                return new StandInDelivery(
                        DeliveryResult.pending("sample-consumer status " + response.statusCode()), traceId);
            } catch (Exception ex) {
                breaker.recordFailure();
                span.recordException(ex);
                return new StandInDelivery(DeliveryResult.pending(clip(ex.getMessage())), traceId);
            }
        } finally {
            span.end();
        }
    }

    private HttpRequest build(TaskRecordedNotice notice, boolean forceFailure) throws JsonProcessingException {
        Map<String, String> carrier = new LinkedHashMap<>();
        openTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), carrier, Map::put);
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(consumerBaseUrl + "/inbox/task-recorded"))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .header("Authorization", basic())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(notice)));
        carrier.forEach(builder::header);
        if (forceFailure) {
            builder.header("X-Sample-Fail", "true");
        }
        return builder.build();
    }

    private String basic() {
        String raw = username + ":" + password;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static String stripTrailingSlash(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("sample consumer url is missing");
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String clip(String reason) {
        if (reason == null || reason.isBlank()) {
            return "sample-consumer call failed";
        }
        return reason.length() <= 512 ? reason : reason.substring(0, 512);
    }
}
