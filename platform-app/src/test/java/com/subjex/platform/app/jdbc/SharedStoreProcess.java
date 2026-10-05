package com.subjex.platform.app.jdbc;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * SharedStoreProcess — 共享存放的另一个进程：测试把它当成独立的 JVM 启动。
 * <p>
 * {@code write <url>} registers sample-consumer, overrides one key, and takes the lock, then exits.
 * {@code read <url>} prints what it sees, one fact per line, then exits.
 * {@code write <url>} 登记 sample-consumer、覆盖一个键、拿到锁，然后退出。
 * {@code read <url>} 每行打印一条看到的事实，然后退出。
 */
public final class SharedStoreProcess {

    private SharedStoreProcess() {}

    public static void main(String[] args) {
        String action = args[0];
        DataSource source = H2PlatformTables.migrated(args[1], H2PlatformTables.Mode.POSTGRESQL);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        JdbcServiceRegistry registry = new JdbcServiceRegistry(jdbc, Clock.systemUTC());
        JdbcConfigOverride overrides = new JdbcConfigOverride(jdbc, Clock.systemUTC());
        JdbcRowLock lock = new JdbcRowLock(jdbc, Clock.systemUTC());
        if ("write".equals(action)) {
            registry.register(new ServiceEndpoint("sample-consumer", "127.0.0.1", 19081));
            overrides.override("platform.demo.message", "from-process-one");
            if (!lock.tryAcquire("nightly-report", "process-one", Duration.ofMinutes(5))) {
                throw new IllegalStateException("process one could not take the lock");
            }
            System.out.println("written");
        } else {
            for (ServiceEndpoint endpoint : registry.endpoints()) {
                System.out.println("endpoint " + endpoint.serviceName() + " " + endpoint.host() + ":" + endpoint.port());
            }
            System.out.println("override " + overrides.lookup("platform.demo.message").orElse("-"));
            System.out.println("lock-for-process-three " + lock.tryAcquire("nightly-report", "process-three", Duration.ofMinutes(5)));
        }
        jdbc.execute("SHUTDOWN");
    }
}
