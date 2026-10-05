package com.subjex.platform.app.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Three processes, one database file, each one restarted — 三个进程、一个库文件，每个都重启过。
 * <p>
 * Process one is a separate JVM: it registers, overrides, and takes the lock, then exits. This test JVM opens the same
 * database afterwards, sees those rows, is refused the lock, and writes its own rows. Process three is another new JVM
 * and sees both writers. No row lives only in a process's memory.
 * 进程一是独立的 JVM：登记、覆盖、拿锁，然后退出。这个测试 JVM 随后打开同一个库，看到那些行、拿不到锁，再写自己的行。
 * 进程三是又一个新 JVM，能看到两个写入者的行。没有任何一行只活在某个进程的内存里。
 */
class SharedDatabaseAcrossProcessesTest {

    @TempDir
    Path directory;

    @Test
    void registrationsOverridesAndTheLockSurviveProcessRestarts() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("shared").toAbsolutePath() + ";AUTO_SERVER=TRUE";

        assertEquals(List.of("written"), runProcess("write", url));

        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(url, H2PlatformTables.Mode.POSTGRESQL));
        JdbcServiceRegistry registry = new JdbcServiceRegistry(jdbc, Clock.systemUTC());
        JdbcConfigOverride overrides = new JdbcConfigOverride(jdbc, Clock.systemUTC());
        JdbcRowLock lock = new JdbcRowLock(jdbc, Clock.systemUTC());
        assertEquals(Optional.of(new ServiceEndpoint("sample-consumer", "127.0.0.1", 19081)),
                registry.resolve("sample-consumer"));
        assertEquals(Optional.of("from-process-one"), overrides.lookup("platform.demo.message"));
        assertFalse(lock.tryAcquire("nightly-report", "process-two", Duration.ofMinutes(5)),
                "process one still holds the lock after it exited, until the hold expires");
        registry.register(new ServiceEndpoint("platform-app", "127.0.0.1", 8080));
        overrides.override("platform.demo.message", "from-process-two");
        jdbc.execute("SHUTDOWN");

        List<String> seen = runProcess("read", url);
        assertTrue(seen.contains("endpoint platform-app 127.0.0.1:8080"), seen.toString());
        assertTrue(seen.contains("endpoint sample-consumer 127.0.0.1:19081"), seen.toString());
        assertTrue(seen.contains("override from-process-two"), seen.toString());
        assertTrue(seen.contains("lock-for-process-three false"), seen.toString());
    }

    private static List<String> runProcess(String action, String url) throws IOException, InterruptedException {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(
                        java.toString(),
                        "-cp", System.getProperty("java.class.path"),
                        SharedStoreProcess.class.getName(),
                        action,
                        url)
                .redirectErrorStream(true)
                .start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("child process did not finish");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output.lines()
                .filter(line -> line.startsWith("written") || line.startsWith("endpoint ")
                        || line.startsWith("override ") || line.startsWith("lock-for-"))
                .toList();
    }
}
