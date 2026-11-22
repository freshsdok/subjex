package com.subjex.platform.app.admin;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.availability.ApplicationAvailability;

/**
 * LoadDashboardService — snapshot of already-produced Micrometer meters for the operator console.
 * 把进程里已有的 Micrometer 指标做成操作员控制台快照。
 * <p>
 * This is not a Grafana/Prometheus product and does not invent series. Missing binders
 * (no Hikari pool, no Tomcat, no GC timer yet) are reported as absent. Rate-limit and
 * delivery-circuit state are not published as actuator meters, so they are not included.
 * 这不是 Grafana/Prometheus 产品，也不编造指标。缺少的绑定（没有连接池、没有 Tomcat、尚未有
 * GC 计时器）如实标为没有。限流与投递熔断目前未作为 actuator 指标发布，故不列入。
 */
public final class LoadDashboardService {

    public static final String HTTP_SERVER_REQUESTS = "http.server.requests";
    public static final String JVM_MEMORY_USED = "jvm.memory.used";
    public static final String JVM_MEMORY_MAX = "jvm.memory.max";
    public static final String JVM_THREADS_LIVE = "jvm.threads.live";
    public static final String JVM_THREADS_PEAK = "jvm.threads.peak";
    public static final String PROCESS_UPTIME = "process.uptime";
    public static final String PROCESS_CPU = "process.cpu.usage";
    public static final String PROCESS_START = "process.start.time";
    public static final String HIKARI_ACTIVE = "hikaricp.connections.active";
    public static final String HIKARI_IDLE = "hikaricp.connections.idle";
    public static final String HIKARI_PENDING = "hikaricp.connections.pending";
    public static final String HIKARI_MAX = "hikaricp.connections.max";
    public static final String LOGBACK_EVENTS = "logback.events";
    public static final String TOMCAT_BUSY = "tomcat.threads.busy";
    public static final String TOMCAT_CURRENT = "tomcat.threads.current";
    public static final String TOMCAT_MAX = "tomcat.threads.config.max";

    private final MeterRegistry registry;
    private final ApplicationAvailability availability;
    private final Clock clock;

    public LoadDashboardService(
            MeterRegistry registry, ApplicationAvailability availability, Clock clock) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.availability = Objects.requireNonNull(availability, "availability");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public LoadSnapshot snapshot() {
        return new LoadSnapshot(
                clock.instant(),
                availability.getLivenessState().name(),
                availability.getReadinessState().name(),
                processSnapshot(),
                jvmSnapshot(),
                httpSnapshot(),
                poolSnapshot(),
                tomcatSnapshot(),
                logSnapshot(),
                false,
                false);
    }

    private ProcessSnapshot processSnapshot() {
        return new ProcessSnapshot(
                timeOrGaugeSeconds(PROCESS_UPTIME),
                gaugeValue(PROCESS_CPU),
                startTime(PROCESS_START));
    }

    private JvmSnapshot jvmSnapshot() {
        TimerTotals gc = firstTimerTotals("jvm.gc.pause", "jvm.gc.duration");
        return new JvmSnapshot(
                sumGauges(JVM_MEMORY_USED, "area", "heap"),
                sumGauges(JVM_MEMORY_MAX, "area", "heap"),
                sumGauges(JVM_MEMORY_USED, "area", "nonheap"),
                gaugeValue(JVM_THREADS_LIVE),
                gaugeValue(JVM_THREADS_PEAK),
                gc == null ? null : gc.count(),
                gc == null ? null : gc.totalSeconds());
    }

    private HttpSnapshot httpSnapshot() {
        TimerTotals totals = timerTotals(HTTP_SERVER_REQUESTS);
        if (totals == null) {
            return new HttpSnapshot(false, null, null, null, List.of());
        }
        TreeMap<String, Long> byStatus = new TreeMap<>();
        for (Timer timer : registry.find(HTTP_SERVER_REQUESTS).timers()) {
            String status = timer.getId().getTag("status");
            if (status == null || status.isBlank()) {
                status = "unknown";
            }
            byStatus.merge(status, timer.count(), Long::sum);
        }
        List<StatusCount> statuses = new ArrayList<>();
        byStatus.forEach((status, count) -> statuses.add(new StatusCount(status, count)));
        return new HttpSnapshot(true, totals.count(), totals.totalSeconds(), totals.maxSeconds(), statuses);
    }

    private PoolSnapshot poolSnapshot() {
        Double active = sumAllGauges(HIKARI_ACTIVE);
        Double idle = sumAllGauges(HIKARI_IDLE);
        Double pending = sumAllGauges(HIKARI_PENDING);
        Double max = sumAllGauges(HIKARI_MAX);
        boolean present = active != null || idle != null || pending != null || max != null;
        return new PoolSnapshot(present, active, idle, pending, max);
    }

    private TomcatSnapshot tomcatSnapshot() {
        Double busy = gaugeValue(TOMCAT_BUSY);
        Double current = gaugeValue(TOMCAT_CURRENT);
        Double max = gaugeValue(TOMCAT_MAX);
        boolean present = busy != null || current != null || max != null;
        return new TomcatSnapshot(present, busy, current, max);
    }

    private LogSnapshot logSnapshot() {
        List<Counter> counters = new ArrayList<>(registry.find(LOGBACK_EVENTS).counters());
        if (counters.isEmpty()) {
            return new LogSnapshot(false, List.of());
        }
        TreeMap<String, Double> byLevel = new TreeMap<>();
        for (Counter counter : counters) {
            String level = counter.getId().getTag("level");
            if (level == null || level.isBlank()) {
                level = "unknown";
            }
            byLevel.merge(level, counter.count(), Double::sum);
        }
        List<LevelCount> levels = new ArrayList<>();
        byLevel.forEach((level, count) -> levels.add(new LevelCount(level, count.longValue())));
        return new LogSnapshot(true, levels);
    }

    private Double timeOrGaugeSeconds(String name) {
        TimeGauge timeGauge = registry.find(name).timeGauge();
        if (timeGauge != null) {
            return timeGauge.value(TimeUnit.SECONDS);
        }
        return gaugeValue(name);
    }

    private Instant startTime(String name) {
        Double seconds = timeOrGaugeSeconds(name);
        if (seconds == null || !Double.isFinite(seconds) || seconds <= 0) {
            return null;
        }
        // Micrometer process.start.time is seconds since the Unix epoch.
        // Micrometer 的 process.start.time 是 Unix 纪元秒。
        return Instant.ofEpochMilli(Math.round(seconds * 1000.0d));
    }

    private Double gaugeValue(String name) {
        Gauge gauge = registry.find(name).gauge();
        if (gauge == null) {
            return null;
        }
        double value = gauge.value();
        return Double.isFinite(value) ? value : null;
    }

    private Double sumGauges(String name, String tagKey, String tagValue) {
        double sum = 0;
        boolean any = false;
        for (Gauge gauge : registry.find(name).tag(tagKey, tagValue).gauges()) {
            double value = gauge.value();
            if (Double.isFinite(value)) {
                any = true;
                sum += value;
            }
        }
        return any ? sum : null;
    }

    private Double sumAllGauges(String name) {
        double sum = 0;
        boolean any = false;
        for (Gauge gauge : registry.find(name).gauges()) {
            double value = gauge.value();
            if (Double.isFinite(value)) {
                any = true;
                sum += value;
            }
        }
        return any ? sum : null;
    }

    private TimerTotals firstTimerTotals(String... names) {
        for (String name : names) {
            TimerTotals totals = timerTotals(name);
            if (totals != null) {
                return totals;
            }
        }
        return null;
    }

    private TimerTotals timerTotals(String name) {
        double total = 0;
        double max = 0;
        long count = 0;
        boolean any = false;
        for (Timer timer : registry.find(name).timers()) {
            any = true;
            count += timer.count();
            total += timer.totalTime(TimeUnit.SECONDS);
            max = Math.max(max, timer.max(TimeUnit.SECONDS));
        }
        return any ? new TimerTotals(count, total, max) : null;
    }

    private record TimerTotals(long count, double totalSeconds, double maxSeconds) {}

    public record LoadSnapshot(
            Instant collectedAt,
            String liveness,
            String readiness,
            ProcessSnapshot process,
            JvmSnapshot jvm,
            HttpSnapshot http,
            PoolSnapshot jdbcPool,
            TomcatSnapshot tomcat,
            LogSnapshot log,
            boolean rateLimitMetricsPresent,
            boolean circuitBreakerMetricsPresent) {}

    public record ProcessSnapshot(Double uptimeSeconds, Double cpuUsage, Instant startedAt) {}

    public record JvmSnapshot(
            Double heapUsedBytes,
            Double heapMaxBytes,
            Double nonHeapUsedBytes,
            Double threadsLive,
            Double threadsPeak,
            Long gcPauseCount,
            Double gcPauseSeconds) {}

    public record HttpSnapshot(
            boolean present,
            Long requestCount,
            Double totalTimeSeconds,
            Double maxSeconds,
            List<StatusCount> byStatus) {}

    public record StatusCount(String status, long count) {}

    public record PoolSnapshot(
            boolean present, Double active, Double idle, Double pending, Double max) {}

    public record TomcatSnapshot(boolean present, Double busy, Double current, Double max) {}

    public record LogSnapshot(boolean present, List<LevelCount> byLevel) {}

    public record LevelCount(String level, long count) {}
}
