package com.subjex.platform.app.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.config.ConfigOverrideStore;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRoster;
import com.subjex.platform.contract.lock.DistributedLockPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Two repository instances on one H2 database share registrations, overrides, and the lock.
 * 同一份 H2 库上的两个仓库实例共享登记、覆盖和锁。
 * <p>
 * That is the multi-process / restart proof without Docker: rows live in the database, not in a JVM map.
 * 这是没有 Docker 时的多进程 / 重启证明：行在库里，不在 JVM 的表里。
 */
class SharedRegistryConfigLockTest {

    private DataSource source;
    private Clock clock;

    @BeforeEach
    void openSharedDatabase() {
        // One named memory database; closing the first instance does not drop the rows.
        // 一份具名内存库；关掉第一个实例不会丢掉行。
        source = H2PlatformTables.migrated(
                "jdbc:h2:mem:subjex-shared-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1",
                H2PlatformTables.Mode.POSTGRESQL);
        clock = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneOffset.UTC);
    }

    @Test
    void secondRegistrySeesRegistrationAfterFirstInstanceIsGone() {
        ServiceRoster writer = new JdbcServiceRegistry(new JdbcTemplate(source), clock);
        writer.register(new ServiceEndpoint("sample-consumer", "127.0.0.1", 19081));

        // Simulate another process (or a restart): a new repository on the same database.
        // 模拟另一个进程（或一次重启）：同一库上的新仓库。
        ServiceRoster reader = new JdbcServiceRegistry(new JdbcTemplate(source), clock);
        Optional<ServiceEndpoint> found = reader.resolve("sample-consumer");
        assertTrue(found.isPresent());
        assertEquals("127.0.0.1", found.get().host());
        assertEquals(19081, found.get().port());
        assertEquals(1, reader.endpoints().size());
    }

    @Test
    void secondOverrideStoreSeesValueAfterFirstInstanceIsGone() {
        ConfigOverrideStore writer = new JdbcConfigOverride(new JdbcTemplate(source), clock);
        writer.override("platform.discovery.static.platform-app.port", "9090");

        ConfigOverrideStore reader = new JdbcConfigOverride(new JdbcTemplate(source), clock);
        assertEquals(Optional.of("9090"), reader.lookup("platform.discovery.static.platform-app.port"));
        assertTrue(reader.keys().contains("platform.discovery.static.platform-app.port"));
    }

    @Test
    void twoLockInstancesContendAndExpiredHoldCanBeTaken() {
        DistributedLockPort first = new JdbcRowLock(new JdbcTemplate(source), clock);
        DistributedLockPort second = new JdbcRowLock(new JdbcTemplate(source), clock);

        assertTrue(first.tryAcquire("submit:tenant-north", "owner-a", Duration.ofSeconds(30)));
        assertFalse(second.tryAcquire("submit:tenant-north", "owner-b", Duration.ofSeconds(30)));
        assertTrue(first.release("submit:tenant-north", "owner-a"));
        assertTrue(second.tryAcquire("submit:tenant-north", "owner-b", Duration.ofSeconds(30)));
        assertFalse(first.release("submit:tenant-north", "owner-a"));
        assertTrue(second.release("submit:tenant-north", "owner-b"));

        Clock later = Clock.fixed(Instant.parse("2026-10-05T03:01:00Z"), ZoneOffset.UTC);
        DistributedLockPort early = new JdbcRowLock(new JdbcTemplate(source), clock);
        DistributedLockPort late = new JdbcRowLock(new JdbcTemplate(source), later);
        assertTrue(early.tryAcquire("submit:tenant-south", "owner-a", Duration.ofSeconds(30)));
        // Still held at T+0 for owner-b; at T+60s the row is expired and owner-b takes it.
        // T+0 时 owner-b 仍拿不到；T+60s 行已过期，owner-b 接手。
        assertFalse(new JdbcRowLock(new JdbcTemplate(source), clock)
                .tryAcquire("submit:tenant-south", "owner-b", Duration.ofSeconds(30)));
        assertTrue(late.tryAcquire("submit:tenant-south", "owner-b", Duration.ofSeconds(30)));
        assertTrue(late.release("submit:tenant-south", "owner-b"));
    }

    @Test
    void migrationCreatesSharedTablesInMysqlModeToo() {
        DataSource mysql = H2PlatformTables.migrated(H2PlatformTables.Mode.MYSQL);
        JdbcTemplate jdbc = new JdbcTemplate(mysql);
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM service_endpoint", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM config_override", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_lock", Integer.class));
        new JdbcServiceRegistry(jdbc, clock).register(new ServiceEndpoint("platform-app", "127.0.0.1", 8080));
        assertEquals(Optional.of(new ServiceEndpoint("platform-app", "127.0.0.1", 8080)),
                new JdbcServiceRegistry(jdbc, clock).resolve("platform-app"));
    }
}
