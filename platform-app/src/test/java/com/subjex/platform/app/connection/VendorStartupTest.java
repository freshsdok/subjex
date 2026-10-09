package com.subjex.platform.app.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.PlatformApplication;
import com.subjex.platform.contract.connection.ConnectionVendor;
import com.subjex.platform.contract.connection.RelationalConnectionPort;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceEndpoint;
import com.subjex.platform.contract.discovery.ServiceRoster;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.Base64;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Live startup on MySQL and PostgreSQL — 在 MySQL 和 PostgreSQL 上真实启动。
 * <p>
 * Skipped when Docker is absent. A skip is not a pass against a live database.
 * Probes are asserted on local.management.port (P2 separate management port).
 * 没有 Docker 时跳过。跳过不等于已经对着真实数据库通过。探针断言管理口（P2）。
 */
class VendorStartupTest {

    /** Operator seeded by the local profile inside the container — local profile 在容器库里写入的操作员。 */
    private static final String OPERATOR_NAME = "platform-operator";
    private static final String OPERATOR_PASSWORD = "change-me";

    @Test
    void platformAppStartsOnMysql() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is unavailable; MySQL startup was not executed against a live database");
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
        try {
            mysql.start();
            assertReady(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(),
                    "com.mysql.cj.jdbc.Driver", "mysql", ConnectionVendor.MYSQL);
        } finally {
            mysql.stop();
        }
    }

    @Test
    void platformAppStartsOnPostgresql() throws Exception {
        Assumptions.assumeTrue(dockerAvailable(),
                "Docker is unavailable; PostgreSQL startup was not executed against a live database");
        PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        try {
            postgres.start();
            assertReady(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword(),
                    "org.postgresql.Driver", "postgresql", ConnectionVendor.POSTGRESQL);
        } finally {
            postgres.stop();
        }
    }

    private static void assertReady(
            String jdbcUrl,
            String username,
            String password,
            String driver,
            String vendor,
            ConnectionVendor expected) throws Exception {
        // Command-line arguments outrank application.yml and environment variables, so the container always wins.
        // properties(...) would only set defaults, and application.yml would point back at a local database.
        // 命令行参数优先于 application.yml 和环境变量，所以一定连到容器。
        // properties(...) 只是默认值，application.yml 会把连接拉回本机数据库。
        String[] containerArguments = {
                // LOCAL ONLY seed, written into the container database only / 仅限本地的种子，只写进容器数据库
                "--spring.profiles.active=local",
                "--platform.local-operator.name=" + OPERATOR_NAME,
                "--platform.local-operator.password=" + OPERATOR_PASSWORD,
                "--platform.connection.vendor=" + vendor,
                "--spring.datasource.url=" + jdbcUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.datasource.driver-class-name=" + driver,
                "--server.port=0",
                // P2: probes live on the management port, not the business port.
                // P2：探针在管理口，不在业务口。
                "--management.server.port=0",
                "--platform.storage.directory=target/object-store",
                "--platform.delivery.hmac-secret=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "--platform.delivery.allow-insecure=true"};
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PlatformApplication.class)
                .run(containerArguments)) {
            // Prove the context is on the container before trusting anything else / 先证明连的是容器，再看别的
            assertEquals(jdbcUrl, context.getEnvironment().getProperty("spring.datasource.url"));
            try (Connection connection = context.getBean(DataSource.class).getConnection()) {
                assertEquals(jdbcUrl, connection.getMetaData().getURL());
            }
            assertEquals(expected, context.getBean(RelationalConnectionPort.class).vendor());
            Long tasks = context.getBean(JdbcTemplate.class)
                    .queryForObject("SELECT COUNT(*) FROM platform_task", Long.class);
            assertEquals(0L, tasks);
            String port = context.getEnvironment().getProperty("local.server.port");
            String managementPort = context.getEnvironment().getProperty("local.management.port");
            if (managementPort == null || managementPort.isBlank()) {
                managementPort = port;
            }
            // The shared table holds the bound random port, not zero / 共享登记表里是绑定的随机端口，不是零
            ServiceEndpoint registered = context.getBean(ServiceRoster.class)
                    .resolve(PlatformServiceNames.PLATFORM_APP).orElseThrow();
            assertEquals(Integer.parseInt(port), registered.port());
            HttpClient client = HttpClient.newHttpClient();
            // Anonymous probes on the management port (P2)
            HttpResponse<String> liveness = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + managementPort + "/actuator/health/liveness"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> readiness = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + managementPort + "/actuator/health/readiness"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, liveness.statusCode(), "liveness on management port");
            assertEquals(200, readiness.statusCode(), "readiness on management port");
            assertTrue(liveness.body().contains("UP"));
            assertTrue(readiness.body().contains("UP"));
            // Business-port probe path is not the anonymous contract when management is separate (often 401).
            HttpResponse<String> businessReadiness = client.send(
                    HttpRequest.newBuilder(URI.create(
                                    "http://127.0.0.1:" + port + "/actuator/health/readiness"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(
                    businessReadiness.statusCode() == 401 || businessReadiness.statusCode() == 404,
                    "business-port readiness should be 401/404 when management port is set, got "
                            + businessReadiness.statusCode());
            String basic = Base64.getEncoder().encodeToString(
                    (OPERATOR_NAME + ":" + OPERATOR_PASSWORD).getBytes(StandardCharsets.UTF_8));
            HttpResponse<String> admin = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/admin/health"))
                            .header("Authorization", "Basic " + basic)
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, admin.statusCode());
        }
    }

    private static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ex) {
            return false;
        }
    }
}
