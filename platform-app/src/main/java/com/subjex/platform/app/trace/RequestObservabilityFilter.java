package com.subjex.platform.app.trace;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * RequestObservabilityFilter — OBS-2: correlation id + W3C traceparent extract + MDC.
 * <p>
 * Accepts {@code X-Correlation-Id} / {@code X-Request-Id} (or mints a UUID), extracts
 * {@code traceparent} via the platform OpenTelemetry propagator, starts a short SERVER span,
 * and puts {@code correlationId} + {@code traceId} into SLF4J MDC for structured logs.
 * Echoes both ids on the response. Gateway/APIs should forward {@code traceparent} unchanged.
 * 接受关联 id（或生成），用 OTel 传播器抽取 traceparent，开短 SERVER 跨度，写入 MDC；响应回写。
 * 网关/API 应原样转发 traceparent。
 */
public final class RequestObservabilityFilter extends OncePerRequestFilter {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String MDC_CORRELATION = "correlationId";
    public static final String MDC_TRACE = "traceId";

    private static final TextMapGetter<HttpServletRequest> GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(HttpServletRequest carrier) {
            return () -> carrier.getHeaderNames().asIterator();
        }

        @Override
        public String get(HttpServletRequest carrier, String key) {
            if (carrier == null || key == null) {
                return null;
            }
            return carrier.getHeader(key);
        }
    };

    private final OpenTelemetry openTelemetry;

    public RequestObservabilityFilter(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlation = firstNonBlank(request.getHeader(CORRELATION_HEADER), request.getHeader(REQUEST_ID_HEADER));
        if (correlation == null) {
            correlation = UUID.randomUUID().toString().replace("-", "");
        }
        TextMapPropagator propagator = openTelemetry.getPropagators().getTextMapPropagator();
        Context extracted = propagator.extract(Context.current(), request, GETTER);
        Tracer tracer = openTelemetry.getTracer("platform-app");
        Span span = tracer.spanBuilder(request.getMethod() + " " + request.getRequestURI())
                .setSpanKind(SpanKind.SERVER)
                .setParent(extracted)
                .startSpan();
        String traceId = span.getSpanContext().getTraceId();
        response.setHeader(CORRELATION_HEADER, correlation);
        if (span.getSpanContext().isValid()) {
            response.setHeader("traceparent", formatTraceparent(span));
        }
        MDC.put(MDC_CORRELATION, correlation);
        if (traceId != null && !traceId.isBlank() && !"00000000000000000000000000000000".equals(traceId)) {
            MDC.put(MDC_TRACE, traceId);
        }
        try (Scope ignored = span.makeCurrent()) {
            filterChain.doFilter(request, response);
            int status = response.getStatus();
            if (status >= 500) {
                span.setStatus(StatusCode.ERROR);
            }
        } catch (Exception ex) {
            span.recordException(ex);
            span.setStatus(StatusCode.ERROR);
            throw ex;
        } finally {
            span.end();
            MDC.remove(MDC_CORRELATION);
            MDC.remove(MDC_TRACE);
        }
    }

    private static String formatTraceparent(Span span) {
        var ctx = span.getSpanContext();
        String flags = ctx.isSampled() ? "01" : "00";
        return "00-" + ctx.getTraceId() + "-" + ctx.getSpanId() + "-" + flags;
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
