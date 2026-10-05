package com.subjex.platform.app.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.config.ConfigEntry;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigOrigin;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.discovery.FallbackServiceRegistry;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared JDBC registry, config overrides, and row lock — 共享 JDBC 名册、配置覆盖和行锁。
 * <p>
 * Runs on H2 in PostgreSQL and MySQL mode with the real Flyway scripts.
 * 在 H2 的 PostgreSQL 与 MySQL 模式上执行真实的 Flyway 脚本。
 */
class SharedStoreTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void registryReplacesAndListsEndpoints(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcServiceRegistry registry = new JdbcServiceRegistry(jdbc, Clock.systemUTC());

        registry.register(new ServiceEndpoint("sample-consumer", "127.0.0.1", 19081));
        registry.register(new ServiceEndpoint("platform-app", "127.0.0.1", 8080));
        registry.register(new ServiceEndpoint("sample-consumer", "10.0.0.7", 19082));

        assertEquals(Optional.of(new ServiceEndpoint("sample-consumer", "10.0.0.7", 19082)),
                registry.resolve("sample-consumer"));
        assertEquals(List.of("platform-app", "sample-consumer"),
                registry.endpoints().stream().map(ServiceEndpoint::serviceName).toList());
        assertEquals(Optional.empty(), registry.resolve("nobody"));
        assertEquals(Optional.empty(), registry.resolve(" "));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void staticFallbackStillAnswersWhenTheTableHasNoRow(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        String hostKey = StaticServiceFallback.hostKey("platform-app");
        String portKey = StaticServiceFallback.portKey("platform-app");
        FallbackServiceRegistry registry = new FallbackServiceRegistry(
                new JdbcServiceRegistry(jdbc, Clock.systemUTC()),
                StaticServiceFallback.fromConfig(
                        new LocalApplicationConfig(Map.of(hostKey, "192.0.2.1", portKey, "8080")::get), "platform-app"));

        assertEquals(Optional.of(new ServiceEndpoint("platform-app", "192.0.2.1", 8080)), registry.resolve("platform-app"));
        registry.register(new ServiceEndpoint("platform-app", "127.0.0.1", 9090));
        assertEquals(Optional.of(new ServiceEndpoint("platform-app", "127.0.0.1", 9090)), registry.resolve("platform-app"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void storedOverrideWinsOverLocalConfig(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        JdbcConfigOverride overrides = new JdbcConfigOverride(jdbc, Clock.systemUTC());
        ConfigListing listing = new ConfigListing(
                overrides, new LocalApplicationConfig(Map.of("platform.demo.message", "local")::get));

        assertEquals(Optional.of(new ConfigEntry("platform.demo.message", "local", ConfigOrigin.LOCAL)),
                listing.entry("platform.demo.message"));
        overrides.override("platform.demo.message", "first");
        overrides.override("platform.demo.message", "second");
        overrides.override("platform.extra", "x");

        assertEquals(Optional.of(new ConfigEntry("platform.demo.message", "second", ConfigOrigin.OVERRIDE)),
                listing.entry("platform.demo.message"));
        assertEquals(Set.of("platform.demo.message", "platform.extra"), overrides.keys());
        assertEquals(2, listing.rows(List.of("platform.demo.message")).size());
        assertThrows(IllegalArgumentException.class, () -> overrides.override("k", " "));
        assertThrows(IllegalArgumentException.class, () -> overrides.override(" ", "v"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void rowLockHasOneOwnerUntilItExpires(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));
        MovableClock clock = new MovableClock(Instant.parse("2026-10-05T00:00:00Z"));
        JdbcRowLock lock = new JdbcRowLock(jdbc, clock);
        Duration hold = Duration.ofSeconds(30);

        assertTrue(lock.tryAcquire("nightly", "owner-a", hold));
        assertFalse(lock.tryAcquire("nightly", "owner-b", hold));
        assertTrue(lock.tryAcquire("nightly", "owner-a", hold), "the owner may extend its hold");
        assertFalse(lock.release("nightly", "owner-b"));
        assertEquals("owner-a", jdbc.queryForObject(
                "SELECT owner_token FROM platform_lock WHERE lock_name = 'nightly'", String.class));

        clock.advance(Duration.ofSeconds(31));
        assertTrue(lock.tryAcquire("nightly", "owner-b", hold), "an expired hold may be taken over");
        assertFalse(lock.release("nightly", "owner-a"));
        assertTrue(lock.release("nightly", "owner-b"));
        assertFalse(lock.release("nightly", "owner-b"));
        assertTrue(lock.tryAcquire("nightly", "owner-a", hold));
        assertThrows(IllegalArgumentException.class, () -> lock.tryAcquire("nightly", "owner-a", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> lock.tryAcquire(" ", "owner-a", hold));
    }

    /** A clock a test moves forward — 测试拨动的时钟。 */
    static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
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
