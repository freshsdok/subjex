package com.subjex.platform.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.delivery.JdbcDeliveryCircuitBreakerPort;
import com.subjex.platform.app.ratelimit.JdbcRateLimitPort;
import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * RateLimitCircuitObservationTest — read live windows and breaker state; no invented rejection counts.
 * 只读已有时间窗与熔断开合；不编造拒绝次数或 Grafana 指标。
 */
class RateLimitCircuitObservationTest {

    private static final Instant NOW = Instant.parse("2026-11-23T02:00:00Z");

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void processBackendShowsActiveWindowsAndOpenBreakerWithoutRejectionCounts() throws Exception {
        MovableClock clock = new MovableClock(NOW);
        SingleProcessRateLimit limit = new SingleProcessRateLimit(2, Duration.ofMinutes(1), clock);
        DeliveryCircuitBreaker breaker = new DeliveryCircuitBreaker(2, Duration.ofSeconds(15), clock);
        assertThat(limit.permit("tenant-north", "submit-task")).isTrue();
        assertThat(limit.permit("tenant-north", "submit-task")).isTrue();
        assertThat(limit.permit("tenant-north", "submit-task")).isFalse();
        assertThat(limit.permit("tenant-south", "submit-task")).isTrue();
        breaker.recordFailure();
        breaker.recordFailure();

        RateLimitCircuitObservationService service = new RateLimitCircuitObservationService(
                limit, breaker, "process", "process", "outbox", clock);
        MockMvc mvc = mvc(service);
        mvc.perform(get(RateLimitCircuitObservationEndpoint.PATH).with(asPrincipal(operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.collectedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.rateLimit.backend").value("process"))
                .andExpect(jsonPath("$.rateLimit.processLocal").value(true))
                .andExpect(jsonPath("$.rateLimit.permits").value(2))
                .andExpect(jsonPath("$.rateLimit.windowSeconds").value(60))
                .andExpect(jsonPath("$.rateLimit.rejectionCountRecorded").value(false))
                .andExpect(jsonPath("$.rateLimit.truncated").value(false))
                .andExpect(jsonPath("$.rateLimit.windows.length()").value(2))
                .andExpect(jsonPath("$.rateLimit.windows[0].tenantId").value("tenant-north"))
                .andExpect(jsonPath("$.rateLimit.windows[0].actionName").value("submit-task"))
                .andExpect(jsonPath("$.rateLimit.windows[0].used").value(2))
                .andExpect(jsonPath("$.rateLimit.windows[0].remaining").value(0))
                .andExpect(jsonPath("$.rateLimit.windows[1].tenantId").value("tenant-south"))
                .andExpect(jsonPath("$.rateLimit.windows[1].used").value(1))
                .andExpect(jsonPath("$.rateLimit.windows[1].remaining").value(1))
                .andExpect(jsonPath("$.circuit.backend").value("process"))
                .andExpect(jsonPath("$.circuit.processLocal").value(true))
                .andExpect(jsonPath("$.circuit.destinationKey").value("outbox"))
                .andExpect(jsonPath("$.circuit.state").value("open"))
                .andExpect(jsonPath("$.circuit.consecutiveFailures").value(2))
                .andExpect(jsonPath("$.circuit.failureThreshold").value(2))
                .andExpect(jsonPath("$.circuit.openCooldownSeconds").value(15))
                .andExpect(jsonPath("$.circuit.openedAt").value(NOW.toString()));

        clock.advance(Duration.ofMinutes(1));
        var afterWindow = service.snapshot();
        assertThat(afterWindow.rateLimit().windows()).isEmpty();
        clock.advance(Duration.ofSeconds(15));
        assertThat(service.snapshot().circuit().state()).isEqualTo("half-open");
    }

    @Test
    void jdbcBackendReadsSharedRowsAndDoesNotInsertABreakerRowOnObserve() throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL));
        MovableClock clock = new MovableClock(NOW);
        JdbcRateLimitPort limit = new JdbcRateLimitPort(jdbc, 3, Duration.ofMinutes(1), clock);
        JdbcDeliveryCircuitBreakerPort breaker =
                new JdbcDeliveryCircuitBreakerPort(jdbc, "outbox", 3, Duration.ofSeconds(30), clock);
        assertThat(limit.permit("tenant-west", "submit-task")).isTrue();
        assertThat(limit.permit("tenant-west", "submit-task")).isTrue();

        RateLimitCircuitObservationService service = new RateLimitCircuitObservationService(
                limit, breaker, "jdbc", "jdbc", "outbox", clock);
        var snapshot = service.snapshot();
        assertThat(snapshot.rateLimit().processLocal()).isFalse();
        assertThat(snapshot.rateLimit().windows()).hasSize(1);
        assertThat(snapshot.rateLimit().windows().get(0).used()).isEqualTo(2);
        assertThat(snapshot.rateLimit().windows().get(0).remaining()).isEqualTo(1);
        assertThat(snapshot.circuit().processLocal()).isFalse();
        assertThat(snapshot.circuit().state()).isEqualTo("closed");
        assertThat(snapshot.circuit().consecutiveFailures()).isEqualTo(0);
        Integer breakerRows = jdbc.queryForObject("SELECT COUNT(*) FROM delivery_circuit_breaker", Integer.class);
        assertThat(breakerRows).isZero();

        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(service.snapshot().circuit().state()).isEqualTo("open");
        assertThat(service.snapshot().circuit().consecutiveFailures()).isEqualTo(3);
    }

    @Test
    void unauthenticatedIsUnauthorized() throws Exception {
        SingleProcessRateLimit limit = new SingleProcessRateLimit(1, Duration.ofMinutes(1), Clock.fixed(NOW, ZoneOffset.UTC));
        DeliveryCircuitBreaker breaker = new DeliveryCircuitBreaker(3, Duration.ofSeconds(30), Clock.fixed(NOW, ZoneOffset.UTC));
        mvc(new RateLimitCircuitObservationService(limit, breaker, "process", "process", "outbox", Clock.fixed(NOW, ZoneOffset.UTC)))
                .perform(get(RateLimitCircuitObservationEndpoint.PATH))
                .andExpect(status().isUnauthorized());
    }

    private static MockMvc mvc(RateLimitCircuitObservationService service) {
        return MockMvcBuilders.standaloneSetup(new RateLimitCircuitObservationEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    private static OperatorPrincipal operator() {
        return new OperatorPrincipal("ops", "hash", "id-ops", "subject-ops", Set.of("admin.read"), true);
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
