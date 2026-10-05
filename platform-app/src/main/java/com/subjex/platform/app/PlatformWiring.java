package com.subjex.platform.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.subjex.platform.app.connection.ConfiguredConnection;
import com.subjex.platform.app.delivery.OutboxSocketPublisher;
import com.subjex.platform.app.discovery.AddressProbe;
import com.subjex.platform.app.discovery.PlatformSelfRegistrar;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.discovery.TcpAddressProbe;
import com.subjex.platform.app.delivery.SamplePathCircuitBreaker;
import com.subjex.platform.app.extension.TaskDeliveryExtension;
import com.subjex.platform.app.form.FormSideEffectRunner;
import com.subjex.platform.app.form.FormSubmissionStore;
import com.subjex.platform.app.form.JdbcFormSubmissionStore;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.jdbc.JdbcConfigOverride;
import com.subjex.platform.app.jdbc.JdbcRowLock;
import com.subjex.platform.app.jdbc.JdbcServiceRegistry;
import com.subjex.platform.app.jdbc.JdbcIdempotencyPort;
import com.subjex.platform.app.jdbc.JdbcTaskMessagePort;
import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.contract.config.ConfigListing;
import com.subjex.platform.contract.config.ConfigSource;
import com.subjex.platform.contract.config.LocalApplicationConfig;
import com.subjex.platform.contract.config.ConfigOverrideStore;
import com.subjex.platform.contract.config.OverridingConfigSource;
import com.subjex.platform.contract.connection.ConnectionVendor;
import com.subjex.platform.contract.connection.RelationalConnectionPort;
import com.subjex.platform.contract.discovery.FallbackServiceRegistry;
import com.subjex.platform.contract.discovery.PlatformServiceNames;
import com.subjex.platform.contract.discovery.ServiceRegistry;
import com.subjex.platform.contract.discovery.ServiceRoster;
import com.subjex.platform.contract.discovery.StaticServiceFallback;
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
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PlatformWiring — 平台装配：每个端口只注册一个实现，扩展在编译期就在 classpath 上。
 * <p>
 * Idempotency, rate limit, and task message each have one bean. The service roster, config overrides, and the lock
 * live in the shared database, so two processes on one database see the same rows. Static discovery config stays the fallback.
 * 幂等、限流、任务消息各有一个 bean。服务名册、配置覆盖和锁都在共享库里，连同一个库的两个进程看到同样的行。静态发现配置仍是兜底。
 */
@Configuration
public class PlatformWiring {

    /**
     * Overrides in table config_override — 覆盖值在 config_override 表里。
     */
    @Bean
    ConfigOverrideStore configOverrideStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcConfigOverride(jdbc, clock);
    }

    @Bean
    ConfigListing configListing(ConfigOverrideStore configOverrideStore, Environment environment) {
        return new ConfigListing(
                configOverrideStore, new LocalApplicationConfig(key -> environment.getProperty(key)));
    }

    @Bean
    ConfigSource configSource(ConfigOverrideStore configOverrideStore, Environment environment) {
        return new OverridingConfigSource(
                configOverrideStore, new LocalApplicationConfig(key -> environment.getProperty(key)));
    }

    @Bean
    ConfigCatalog configCatalog(ConfigListing configListing, ConfigOverrideStore configOverrideStore) {
        return new ConfigCatalog(configListing, configOverrideStore);
    }

    /**
     * Endpoints in table service_endpoint — 端点在 service_endpoint 表里。
     */
    @Bean
    ServiceRoster serviceRoster(JdbcTemplate jdbc, Clock clock) {
        return new JdbcServiceRegistry(jdbc, clock);
    }

    @Bean
    ServiceRegistry serviceRegistry(ServiceRoster serviceRoster, ConfigSource configSource) {
        return new FallbackServiceRegistry(
                serviceRoster,
                StaticServiceFallback.fromConfig(configSource, PlatformServiceNames.PLATFORM_APP));
    }

    /**
     * A short connect, not a health dashboard — 短连接，不是健康看板。
     */
    @Bean
    AddressProbe addressProbe() {
        return new TcpAddressProbe(Duration.ofMillis(300));
    }

    @Bean
    ServiceCatalog serviceCatalog(ServiceRoster serviceRoster, AddressProbe addressProbe) {
        return new ServiceCatalog(serviceRoster, addressProbe);
    }

    @Bean
    PlatformSelfRegistrar platformSelfRegistrar(
            ServiceRegistry serviceRegistry,
            @Value("${platform.discovery.self-host:127.0.0.1}") String advertisedHost) {
        // The port comes from the started web server, not from server.port — 端口取自已启动的 Web 服务器，不读 server.port。
        return new PlatformSelfRegistrar(serviceRegistry, advertisedHost);
    }

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
    DistributedLockPort distributedLockPort(JdbcTemplate jdbc, Clock clock) {
        return new JdbcRowLock(jdbc, clock);
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

    /**
     * Operators sign in against the tables, not a property — 操作员对着表登录，不对着某个属性。
     */
    @Bean
    JdbcOperatorDirectory operatorDirectory(JdbcTemplate jdbc) {
        return new JdbcOperatorDirectory(jdbc);
    }

    @Bean
    OperatorActionAudit operatorActionAudit(AuditPort auditPort, Clock clock) {
        return new OperatorActionAudit(auditPort, clock);
    }

    @Bean
    SamplePathCircuitBreaker samplePathCircuitBreaker(
            @Value("${platform.delivery.breaker-failure-threshold:3}") int failureThreshold) {
        return new SamplePathCircuitBreaker(failureThreshold);
    }

    @Bean
    OutboxSocketPublisher outboxSocketPublisher(
            @Value("${platform.delivery.consumer-host}") String consumerHost,
            @Value("${platform.delivery.consumer-port}") int consumerPort,
            @Value("${platform.delivery.consumer-username}") String username,
            @Value("${platform.delivery.consumer-password}") String password,
            SamplePathCircuitBreaker breaker,
            OpenTelemetry openTelemetry) {
        return new OutboxSocketPublisher(
                consumerHost, consumerPort, username, password, breaker, openTelemetry);
    }

    @Bean
    TaskMessagePort taskMessagePort(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            IdempotencyPort idempotencyPort,
            AuditPort auditPort,
            DistributedLockPort lock,
            OutboxSocketPublisher publisher,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            Clock clock) {
        return new JdbcTaskMessagePort(
                jdbc, transaction, idempotencyPort, auditPort, lock, publisher, openTelemetry, objectMapper, clock);
    }

    @Bean
    JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
        return new JdbcAdminReader(jdbc);
    }


    /**
     * Run declared form effects through Audit / Task / extension ports — 经审计 / 任务 / 扩展端口执行表单声明的副作用。
     */
    @Bean
    FormSideEffectRunner formSideEffectRunner(
            OperatorActionAudit operatorActionAudit,
            TaskMessagePort taskMessagePort,
            List<PlatformExtension> extensions) {
        return new FormSideEffectRunner(operatorActionAudit, taskMessagePort, extensions);
    }

    /**
     * Accepted form submissions in table form_submission — 已接受的表单提交在 form_submission 表里。
     */
    @Bean
    FormSubmissionStore formSubmissionStore(JdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper) {
        return new JdbcFormSubmissionStore(jdbc, clock, objectMapper);
    }
}
