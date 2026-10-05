package com.subjex.platform.app.connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.PlatformApplication;
import com.subjex.platform.contract.connection.ConnectionVendor;
import com.subjex.platform.contract.connection.RelationalConnectionPort;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
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
 * 没有 Docker 时跳过。跳过不等于已经对着真实数据库通过。
 */
class VendorStartupTest {

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
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(PlatformApplication.class)
                .properties(
                        // LOCAL ONLY seed so the operator below exists / 仅限本地的种子，让下面的操作员存在
                        "spring.profiles.active=local",
                        "platform.connection.vendor=" + vendor,
                        "spring.datasource.url=" + jdbcUrl,
                        "spring.datasource.username=" + username,
                        "spring.datasource.password=" + password,
                        "spring.datasource.driver-class-name=" + driver,
                        "server.port=0",
                        "platform.storage.directory=target/object-store")
                .run()) {
            assertEquals(expected, context.getBean(RelationalConnectionPort.class).vendor());
            Long tasks = context.getBean(JdbcTemplate.class)
                    .queryForObject("SELECT COUNT(*) FROM platform_task", Long.class);
            assertEquals(0L, tasks);
            String port = context.getEnvironment().getProperty("local.server.port");
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> liveness = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/liveness"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> readiness = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health/readiness"))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, liveness.statusCode());
            assertEquals(200, readiness.statusCode());
            assertTrue(liveness.body().contains("UP"));
            assertTrue(readiness.body().contains("UP"));
            String basic = Base64.getEncoder().encodeToString(
                    "platform-operator:change-me".getBytes(StandardCharsets.UTF_8));
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
