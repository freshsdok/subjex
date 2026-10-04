package com.subjex.platform.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.connection.ConfiguredConnection;
import com.subjex.platform.app.delivery.HttpEventStandIn;
import com.subjex.platform.app.delivery.SamplePathCircuitBreaker;
import com.subjex.platform.app.extension.TaskDeliveryExtension;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.lock.SingleProcessLock;
import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.connection.ConnectionVendor;
import com.subjex.platform.contract.connection.RelationalConnectionPort;
import com.subjex.platform.contract.extension.PlatformExtension;
import com.subjex.platform.contract.idempotency.IdempotencyPort;
import com.subjex.platform.contract.lock.DistributedLockPort;
import com.subjex.platform.contract.ratelimit.RateLimitPort;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.task.TaskMessagePort;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import io.opentelemetry.api.OpenTelemetry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PlatformWiring — 平台装配：每个端口只注册一个实现，扩展在编译期就在 classpath 上。
 * <p>
 * Idempotency, rate limit, and task message each have one bean. The lock bean is the single-process implementation.
 * 幂等、限流、任务消息各有一个 bean。锁的 bean 是单进程实现。
 */
@Configuration
public class PlatformWiring {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    TenantGuard tenantGuard() {
        return new DenyWhenTenantMissing();
    }

    @Bean
    RelationalConnectionPort relationalConnectionPort(
            DataSource source, @Value("${platform.connection.vendor}") String vendor) {
        return new ConfiguredConnection(ConnectionVendor.parse(vendor), source);
    }

    @Bean
    DistributedLockPort distributedLockPort(Clock clock) {
        return new SingleProcessLock(clock);
    }

    @Bean
    RateLimitPort rateLimitPort(@Value("${platform.rate-limit.permits:60}") int permits, Clock clock) {
        return new SingleProcessRateLimit(permits, Duration.ofMinutes(1), clock);
    }

    @Bean
    ObjectStorage objectStorage(@Value("${platform.storage.directory:object-store}") String directory) {
        return new LocalDirectoryObjectStorage(Path.of(directory));
    }

    @Bean
    PlatformExtension taskDeliveryExtension() {
        return new TaskDeliveryExtension();
    }

    @Bean
    IdempotencyPort idempotencyPort(JdbcTemplate jdbc, Clock clock) {
        return new JdbcIdempotencyPort(jdbc, clock);
    }

    @Bean
    AuditPort auditPort(JdbcTemplate jdbc) {
        return new JdbcAuditPort(jdbc);
    }

    @Bean
    SamplePathCircuitBreaker samplePathCircuitBreaker(
            @Value("${platform.delivery.breaker-failure-threshold:3}") int failureThreshold) {
        return new SamplePathCircuitBreaker(failureThreshold);
    }

    @Bean
    HttpEventStandIn httpEventStandIn(
            @Value("${platform.delivery.consumer-base-url}") String consumerBaseUrl,
            @Value("${platform.delivery.consumer-username}") String username,
            @Value("${platform.delivery.consumer-password}") String password,
            SamplePathCircuitBreaker breaker,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper) {
        return new HttpEventStandIn(consumerBaseUrl, username, password, breaker, openTelemetry, objectMapper);
    }

    @Bean
    TaskMessagePort taskMessagePort(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            IdempotencyPort idempotencyPort,
            AuditPort auditPort,
            DistributedLockPort lock,
            HttpEventStandIn standIn,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            Clock clock) {
        return new JdbcTaskMessagePort(
                jdbc, transaction, idempotencyPort, auditPort, lock, standIn, openTelemetry, objectMapper, clock);
    }

    @Bean
    JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
        return new JdbcAdminReader(jdbc);
    }
}
