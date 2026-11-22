package com.subjex.platform.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.AvailabilityState;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * LoadDashboardTest — snapshot only already-registered meters; absent binders stay absent.
 * 负载快照只读已登记指标；没有的绑定保持没有。
 */
class LoadDashboardTest {

    private static final Instant NOW = Instant.parse("2026-11-23T00:30:00Z");

    private SimpleMeterRegistry registry;
    private LoadDashboardService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        service = new LoadDashboardService(registry, new FixedAvailability(), Clock.fixed(NOW, ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new LoadDashboardEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyRegistryReportsHealthAndMarksOptionalSectionsAbsent() throws Exception {
        mvc.perform(get(LoadDashboardEndpoint.PATH).with(asPrincipal(operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.liveness").value("CORRECT"))
                .andExpect(jsonPath("$.readiness").value("ACCEPTING_TRAFFIC"))
                .andExpect(jsonPath("$.http.present").value(false))
                .andExpect(jsonPath("$.jdbcPool.present").value(false))
                .andExpect(jsonPath("$.tomcat.present").value(false))
                .andExpect(jsonPath("$.log.present").value(false))
                .andExpect(jsonPath("$.rateLimitMetricsPresent").value(false))
                .andExpect(jsonPath("$.circuitBreakerMetricsPresent").value(false));
        LoadDashboardService.LoadSnapshot empty = service.snapshot();
        assertThat(empty.collectedAt()).isEqualTo(NOW);
        assertThat(empty.process().cpuUsage()).isNull();
        assertThat(empty.process().uptimeSeconds()).isNull();
    }

    @Test
    void snapshotReadsRegisteredStockMetersOnly() throws Exception {
        Gauge.builder("process.cpu.usage", () -> 0.25).register(registry);
        Gauge.builder("jvm.memory.used", () -> 1024.0).tag("area", "heap").tag("id", "Eden").register(registry);
        Gauge.builder("jvm.memory.used", () -> 2048.0).tag("area", "heap").tag("id", "Old").register(registry);
        Gauge.builder("jvm.memory.max", () -> 8192.0).tag("area", "heap").tag("id", "heap").register(registry);
        Gauge.builder("jvm.threads.live", () -> 12.0).register(registry);
        Timer.builder("http.server.requests").tag("status", "200").tag("uri", "/api/v1/me").register(registry)
                .record(Duration.ofMillis(20));
        Timer.builder("http.server.requests").tag("status", "500").tag("uri", "/api/v1/me").register(registry)
                .record(Duration.ofMillis(50));
        Gauge.builder("hikaricp.connections.active", () -> 2.0).tag("pool", "main").register(registry);
        Gauge.builder("hikaricp.connections.idle", () -> 3.0).tag("pool", "main").register(registry);
        Gauge.builder("hikaricp.connections.max", () -> 10.0).tag("pool", "main").register(registry);
        Counter.builder("logback.events").tag("level", "info").register(registry).increment(4);
        Gauge.builder("tomcat.threads.busy", () -> 1.0).register(registry);
        Gauge.builder("tomcat.threads.current", () -> 8.0).register(registry);

        mvc.perform(get(LoadDashboardEndpoint.PATH).with(asPrincipal(operator())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.process.cpuUsage").value(0.25))
                .andExpect(jsonPath("$.jvm.heapUsedBytes").value(3072.0))
                .andExpect(jsonPath("$.jvm.heapMaxBytes").value(8192.0))
                .andExpect(jsonPath("$.jvm.threadsLive").value(12.0))
                .andExpect(jsonPath("$.http.present").value(true))
                .andExpect(jsonPath("$.http.requestCount").value(2))
                .andExpect(jsonPath("$.http.byStatus[0].status").value("200"))
                .andExpect(jsonPath("$.http.byStatus[0].count").value(1))
                .andExpect(jsonPath("$.http.byStatus[1].status").value("500"))
                .andExpect(jsonPath("$.http.byStatus[1].count").value(1))
                .andExpect(jsonPath("$.jdbcPool.present").value(true))
                .andExpect(jsonPath("$.jdbcPool.active").value(2.0))
                .andExpect(jsonPath("$.jdbcPool.idle").value(3.0))
                .andExpect(jsonPath("$.jdbcPool.max").value(10.0))
                .andExpect(jsonPath("$.tomcat.present").value(true))
                .andExpect(jsonPath("$.tomcat.busy").value(1.0))
                .andExpect(jsonPath("$.log.present").value(true))
                .andExpect(jsonPath("$.log.byLevel[0].level").value("info"))
                .andExpect(jsonPath("$.log.byLevel[0].count").value(4))
                .andExpect(jsonPath("$.rateLimitMetricsPresent").value(false))
                .andExpect(jsonPath("$.circuitBreakerMetricsPresent").value(false));

        LoadDashboardService.LoadSnapshot snap = service.snapshot();
        assertThat(snap.http().totalTimeSeconds()).isGreaterThan(0);
        assertThat(snap.jvm().gcPauseCount()).isNull();
    }

    @Test
    void unauthenticatedIsUnauthorized() throws Exception {
        mvc.perform(get(LoadDashboardEndpoint.PATH)).andExpect(status().isUnauthorized());
    }

    private static OperatorPrincipal operator() {
        return new OperatorPrincipal("op-admin", "hash", "id-admin", "sub-admin", Set.of("admin.read"), true);
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private static final class FixedAvailability implements ApplicationAvailability {
        @Override
        @SuppressWarnings("unchecked")
        public <S extends AvailabilityState> S getState(Class<S> stateType) {
            if (stateType == LivenessState.class) {
                return (S) LivenessState.CORRECT;
            }
            if (stateType == ReadinessState.class) {
                return (S) ReadinessState.ACCEPTING_TRAFFIC;
            }
            return null;
        }

        @Override
        public <S extends AvailabilityState> S getState(Class<S> stateType, S defaultState) {
            S state = getState(stateType);
            return state != null ? state : defaultState;
        }

        @Override
        public <S extends AvailabilityState> AvailabilityChangeEvent<S> getLastChangeEvent(Class<S> stateType) {
            return null;
        }
    }
}
