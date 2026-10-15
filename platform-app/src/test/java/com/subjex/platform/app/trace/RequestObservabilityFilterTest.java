package com.subjex.platform.app.trace;

import static org.assertj.core.api.Assertions.assertThat;

import io.opentelemetry.api.OpenTelemetry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * RequestObservabilityFilterTest — OBS-2 correlation id + traceparent + MDC.
 * 目的：关联 id、traceparent 抽取与 MDC。
 */
class RequestObservabilityFilterTest {

    @Test
    void mintsCorrelationPutsMdcAndClears() throws Exception {
        OpenTelemetry otel = new TracingConfiguration().openTelemetry("");
        RequestObservabilityFilter filter = new RequestObservabilityFilter(otel);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/audit");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] seen = new String[2];
        FilterChain chain = (req, res) -> {
            seen[0] = MDC.get(RequestObservabilityFilter.MDC_CORRELATION);
            seen[1] = MDC.get(RequestObservabilityFilter.MDC_TRACE);
        };
        filter.doFilter(request, response, chain);
        assertThat(seen[0]).isNotBlank();
        assertThat(seen[1]).isNotBlank();
        assertThat(response.getHeader(RequestObservabilityFilter.CORRELATION_HEADER)).isEqualTo(seen[0]);
        assertThat(response.getHeader("traceparent")).contains(seen[1]);
        assertThat(MDC.get(RequestObservabilityFilter.MDC_CORRELATION)).isNull();
        assertThat(MDC.get(RequestObservabilityFilter.MDC_TRACE)).isNull();
    }

    @Test
    void respectsIncomingCorrelationAndParentTrace() throws Exception {
        OpenTelemetry otel = new TracingConfiguration().openTelemetry("");
        RequestObservabilityFilter filter = new RequestObservabilityFilter(otel);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/health");
        request.addHeader(RequestObservabilityFilter.CORRELATION_HEADER, "corr-fixed-1");
        request.addHeader("traceparent", "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");
        MockHttpServletResponse response = new MockHttpServletResponse();
        final String[] seen = new String[1];
        FilterChain chain = (req, res) -> seen[0] = MDC.get(RequestObservabilityFilter.MDC_TRACE);
        filter.doFilter(request, response, chain);
        assertThat(response.getHeader(RequestObservabilityFilter.CORRELATION_HEADER)).isEqualTo("corr-fixed-1");
        assertThat(seen[0]).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(response.getHeader("traceparent")).contains("4bf92f3577b34da6a3ce929d0e0e4736");
    }
}
