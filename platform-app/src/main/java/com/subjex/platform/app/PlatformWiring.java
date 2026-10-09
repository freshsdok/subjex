package com.subjex.platform.app;

import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.connection.ConfiguredConnection;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import com.subjex.platform.app.delivery.JdbcDeliveryCircuitBreakerPort;
import com.subjex.platform.app.delivery.OutboxSocketPublisher;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.delivery.DeliveryTransport;
import com.subjex.platform.contract.delivery.OutboxTls;
import com.subjex.platform.contract.delivery.OutboxTransportPolicy;
import com.subjex.platform.app.discovery.AddressProbe;
import com.subjex.platform.app.discovery.PlatformSelfRegistrar;
import com.subjex.platform.app.discovery.ServiceCatalog;
import com.subjex.platform.app.discovery.TcpAddressProbe;
import com.subjex.platform.app.extension.TaskDeliveryExtension;
import com.subjex.entity.declare.EntityCatalog;
import com.subjex.platform.app.entity.GenericEntityStore;
import com.subjex.platform.app.declaration.DeclarationMigrationApplyService;
import com.subjex.platform.app.declaration.DeclarationMigrationAutoEnqueueService;
import com.subjex.platform.app.declaration.DeclarationPromoteService;
import com.subjex.platform.app.declaration.EffectiveDeclarationService;
import com.subjex.platform.app.declaration.InternalDeclarationGit;
import com.subjex.platform.app.declaration.JdbcDeclarationMigrationStore;
import com.subjex.platform.app.declaration.JdbcDeclarationPromoteApprovalStore;
import com.subjex.platform.app.declaration.JdbcDeclarationStore;
import com.subjex.platform.app.form.FormCatalog;
import com.subjex.platform.app.page.PageCatalog;
import com.subjex.platform.app.org.legacy.JdbcOrgDirectory;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.organization.OrganizationScopeResolver;
import com.subjex.platform.app.organization.OrganizationOntologyBackfill;
import com.subjex.platform.app.capability.AiWriteBackSink;
import com.subjex.platform.app.capability.AiWriteConfirmGate;
import com.subjex.platform.app.capability.CapabilityCatalog;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.capability.InMemoryAiWriteConfirmGate;
import com.subjex.platform.app.capability.ModelCompletionClient;
import com.subjex.platform.app.capability.ModelCompletionClients;
import com.subjex.platform.app.capability.NoOpAiWriteBackSink;
import com.subjex.platform.app.form.FormDomainActionRunner;
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
import com.subjex.platform.app.ratelimit.JdbcRateLimitPort;
import com.subjex.platform.app.ratelimit.SingleProcessRateLimit;
import com.subjex.platform.app.security.JdbcOperatorAdmin;
import com.subjex.platform.app.security.JdbcTenantAdmin;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.JdbcOperatorTokenStore;
import com.subjex.platform.app.security.JdbcOperatorLoginLockout;
import com.subjex.platform.app.security.JdbcOperatorMfaStore;
import com.subjex.platform.app.security.AesGcmSecretCipher;
import com.subjex.platform.app.tenant.TenantQuotaService;
import com.subjex.platform.contract.delivery.SecretPlaceholderGuard;
import com.subjex.platform.app.security.OidcProperties;
import com.subjex.platform.app.security.JdbcOidcLoginStore;
import com.subjex.platform.app.security.JdbcOperatorIdpLinkStore;
import com.subjex.platform.app.security.OidcTokenClient;
import com.subjex.platform.app.security.HttpOidcTokenClient;
import com.subjex.platform.app.security.OperatorOidcService;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.storage.LocalDirectoryObjectStorage;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.contract.config.ConfigCenterPort;
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
import javax.net.ssl.SSLContext;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    /** Item 5 lifecycle surface — same catalog instance (Config-5a). / 项 5 生命周期面——与目录同一实例。 */
    @Bean
    ConfigCenterPort configCenterPort(ConfigCatalog configCatalog) {
        return configCatalog;
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
    RateLimitPort rateLimitPort(
            @Value("${platform.rate-limit.backend:process}") String backend,
            @Value("${platform.rate-limit.permits:60}") int permits,
            JdbcTemplate jdbc,
            Clock clock) {
        Duration window = Duration.ofMinutes(1);
        return switch (backend.strip().toLowerCase()) {
            case "process" -> new SingleProcessRateLimit(permits, window, clock);
            case "jdbc" -> new JdbcRateLimitPort(jdbc, permits, window, clock);
            default -> throw new IllegalArgumentException(
                    "platform.rate-limit.backend must be process or jdbc, got: " + backend);
        };
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
    JdbcOperatorTokenStore operatorTokenStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            JdbcOperatorDirectory operatorDirectory,
            Clock clock,
            @Value("${platform.auth.access-token-ttl:PT30M}") Duration accessTokenTtl,
            @Value("${platform.auth.refresh-token-ttl:PT8H}") Duration refreshTokenTtl) {
        return new JdbcOperatorTokenStore(
                jdbc, transactionTemplate, operatorDirectory, clock, accessTokenTtl, refreshTokenTtl);
    }

    @Bean
    JdbcOperatorLoginLockout operatorLoginLockout(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${platform.auth.lockout-max-failures:5}") int maxFailures,
            @Value("${platform.auth.lockout-duration:PT15M}") Duration lockoutDuration) {
        return new JdbcOperatorLoginLockout(
                jdbc, transactionTemplate, clock, maxFailures, lockoutDuration);
    }

    @Bean
    AesGcmSecretCipher mfaSecretCipher(
            @Value("${platform.mfa.encryption-key:}") String encryptionKey,
            @Value("${platform.mfa.encryption-key-previous:}") String previousEncryptionKey,
            Environment environment) {
        String key = encryptionKey == null ? "" : encryptionKey.trim();
        String previous = previousEncryptionKey == null ? "" : previousEncryptionKey.trim();
        String[] profiles = environment.getActiveProfiles();
        if (SecretPlaceholderGuard.isLocalProfile(profiles)) {
            if (key.isEmpty()) {
                // Ephemeral key: enroll/verify fail across restarts until PLATFORM_MFA_ENCRYPTION_KEY is set.
                // 临时密钥：未配置时进程内可用，重启后无法解密已存密钥；生产必须配置。
                byte[] bytes = new byte[32];
                new java.security.SecureRandom().nextBytes(bytes);
                key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
        } else {
            SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                    profiles, "PLATFORM_MFA_ENCRYPTION_KEY", key, true);
            SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                    profiles, "PLATFORM_MFA_ENCRYPTION_KEY_PREVIOUS", previous, false);
        }
        return previous.isEmpty() ? new AesGcmSecretCipher(key) : new AesGcmSecretCipher(key, previous);
    }

    @Bean
    JdbcOperatorMfaStore operatorMfaStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            JdbcOperatorDirectory operatorDirectory,
            AesGcmSecretCipher mfaSecretCipher,
            Clock clock,
            @Value("${platform.mfa.challenge-ttl:PT5M}") Duration challengeTtl,
            @Value("${platform.mfa.issuer:subjex}") String issuer,
            @Value("${platform.mfa.required:false}") boolean required,
            @Value("${platform.mfa.required-for-platform-operator:false}") boolean requiredForPlatformOperator) {
        return new JdbcOperatorMfaStore(
                jdbc,
                transactionTemplate,
                operatorDirectory,
                mfaSecretCipher,
                clock,
                challengeTtl,
                issuer,
                required,
                requiredForPlatformOperator);
    }

    @Bean
    OidcProperties oidcProperties(
            @Value("${platform.oidc.enabled:false}") boolean enabled,
            @Value("${platform.oidc.issuer:}") String issuer,
            @Value("${platform.oidc.client-id:}") String clientId,
            @Value("${platform.oidc.client-secret:}") String clientSecret,
            @Value("${platform.oidc.redirect-uri:}") String redirectUri,
            @Value("${platform.oidc.scopes:openid,profile,email}") String scopesCsv,
            @Value("${platform.oidc.login-ttl:PT10M}") Duration loginTtl) {
        java.util.List<String> scopes = java.util.Arrays.stream(scopesCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        return new OidcProperties(enabled, issuer, clientId, clientSecret, redirectUri, scopes, loginTtl);
    }

    @Bean
    JdbcOidcLoginStore oidcLoginStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            OidcProperties oidcProperties) {
        return new JdbcOidcLoginStore(jdbc, transactionTemplate, clock, oidcProperties.loginTtl());
    }

    @Bean
    JdbcOperatorIdpLinkStore operatorIdpLinkStore(
            JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock) {
        return new JdbcOperatorIdpLinkStore(jdbc, transactionTemplate, clock);
    }

    @Bean
    OidcTokenClient oidcTokenClient(OidcProperties oidcProperties, ObjectMapper objectMapper) {
        return new HttpOidcTokenClient(oidcProperties, objectMapper);
    }

    @Bean
    OperatorOidcService operatorOidcService(
            OidcProperties oidcProperties,
            JdbcOidcLoginStore oidcLoginStore,
            OidcTokenClient oidcTokenClient,
            JdbcOperatorIdpLinkStore operatorIdpLinkStore,
            JdbcOperatorDirectory operatorDirectory,
            JdbcOperatorTokenStore operatorTokenStore) {
        return new OperatorOidcService(
                oidcProperties,
                oidcLoginStore,
                oidcTokenClient,
                operatorIdpLinkStore,
                operatorDirectory,
                operatorTokenStore);
    }

    @Bean
    OperatorActionAudit operatorActionAudit(AuditPort auditPort, Clock clock) {
        return new OperatorActionAudit(auditPort, clock);
    }

    @Bean
    OperatorTenantAccess operatorTenantAccess(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        return new JdbcOperatorTenantAccess(jdbc, transactionTemplate);
    }

    @Bean
    JdbcOperatorAdmin jdbcOperatorAdmin(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
            OperatorTenantAccess operatorTenantAccess) {
        return new JdbcOperatorAdmin(jdbc, transactionTemplate, passwordEncoder, operatorTenantAccess);
    }

    @Bean
    JdbcTenantAdmin jdbcTenantAdmin(JdbcTemplate jdbc) {
        return new JdbcTenantAdmin(jdbc);
    }

    @Bean
    TenantQuotaService tenantQuotaService(
            JdbcTemplate jdbc,
            Clock clock,
            OperatorActionAudit operatorActionAudit,
            @Value("${platform.tenant-quota.daily-submit-limit:10000}") int dailySubmitLimit,
            @Value("${platform.tenant-quota.storage-row-limit:100000}") int storageRowLimit) {
        return new TenantQuotaService(jdbc, clock, operatorActionAudit, dailySubmitLimit, storageRowLimit);
    }

    @Bean
    DeliveryCircuitBreakerPort deliveryCircuitBreakerPort(
            @Value("${platform.delivery.circuit-breaker.backend:process}") String backend,
            @Value("${platform.delivery.breaker-failure-threshold:3}") int failureThreshold,
            @Value("${platform.delivery.circuit-breaker.destination-key:outbox}") String destinationKey,
            JdbcTemplate jdbc,
            Clock clock) {
        Duration cooldown = Duration.ofSeconds(30);
        return switch (backend.strip().toLowerCase()) {
            case "process" -> new DeliveryCircuitBreaker(failureThreshold, cooldown, clock);
            case "jdbc" -> new JdbcDeliveryCircuitBreakerPort(
                    jdbc, destinationKey, failureThreshold, cooldown, clock);
            default -> throw new IllegalArgumentException(
                    "platform.delivery.circuit-breaker.backend must be process or jdbc, got: " + backend);
        };
    }

    /**
     * Socket demo transport (default). Not a multi-consumer bus — see ARCHITECTURE / SECURITY.
     * 套接字演示传输（默认）。不是多消费方总线。
     */
    @Bean
    @ConditionalOnProperty(name = "platform.delivery.transport", havingValue = "socket", matchIfMissing = true)
    DeliveryPort socketDeliveryPort(
            @Value("${platform.delivery.consumer-host}") String consumerHost,
            @Value("${platform.delivery.consumer-port}") int consumerPort,
            @Value("${platform.delivery.hmac-secret}") String hmacSecret,
            @Value("${platform.delivery.tls.enabled:false}") boolean tlsEnabled,
            @Value("${platform.delivery.tls.truststore-path:}") String truststorePath,
            @Value("${platform.delivery.tls.truststore-password:}") String truststorePassword,
            @Value("${platform.delivery.allow-insecure:false}") boolean allowInsecure,
            Environment environment,
            DeliveryCircuitBreakerPort breaker,
            OpenTelemetry openTelemetry,
            Clock clock) {
        SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                environment.getActiveProfiles(), "OUTBOX_HMAC_SECRET", hmacSecret, true);
        SecretPlaceholderGuard.refusePlaceholdersOutsideLocal(
                environment.getActiveProfiles(),
                "OUTBOX_HMAC_SECRET_PREVIOUS",
                environment.getProperty("platform.delivery.hmac-secret-previous", ""),
                false);
        OutboxTransportPolicy.requireReady(hmacSecret, tlsEnabled, allowInsecure, environment.getActiveProfiles());
        SSLContext ssl = null;
        if (tlsEnabled) {
            if (truststorePath == null || truststorePath.isBlank()) {
                throw new IllegalStateException("platform.delivery.tls.truststore-path is required when TLS is enabled");
            }
            ssl = OutboxTls.clientContext(
                    Path.of(truststorePath),
                    truststorePassword == null ? new char[0] : truststorePassword.toCharArray());
        }
        return new OutboxSocketPublisher(
                consumerHost, consumerPort, hmacSecret, ssl, breaker, openTelemetry, clock);
    }

    @Bean
    TaskMessagePort taskMessagePort(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            IdempotencyPort idempotencyPort,
            AuditPort auditPort,
            DistributedLockPort lock,
            ObjectProvider<DeliveryPort> deliveryPort,
            @Value("${platform.delivery.transport:socket}") String transportRaw,
            OpenTelemetry openTelemetry,
            ObjectMapper objectMapper,
            Clock clock) {
        DeliveryPort port = deliveryPort.getIfAvailable();
        if (port == null) {
            DeliveryTransport transport = DeliveryTransport.parse(transportRaw);
            if (transport == DeliveryTransport.KAFKA) {
                throw new IllegalStateException(
                        "platform.delivery.transport=kafka but no DeliveryPort bean — "
                                + "add subjex-outbox-kafka to the classpath (not on the default platform-app startup path)");
            }
            throw new IllegalStateException(
                    "platform.delivery.transport=" + transportRaw.trim()
                            + " but no DeliveryPort bean is registered");
        }
        return new JdbcTaskMessagePort(
                jdbc, transaction, idempotencyPort, auditPort, lock, port, openTelemetry, objectMapper, clock);
    }

    @Bean
    JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
        return new JdbcAdminReader(jdbc);
    }



    /**
     * Classpath entity catalog (entity-declare YAML) — classpath 实体目录（entity-declare YAML）。
     */
    @Bean
    EntityCatalog entityCatalog() {
        return EntityCatalog.load(EntityCatalog.class.getClassLoader());
    }

    /**
     * Metadata-driven JDBC CRUD for generic entities — 元数据驱动的通用实体 JDBC CRUD。
     */
    @Bean
    GenericEntityStore genericEntityStore(JdbcTemplate jdbc) {
        return new GenericEntityStore(jdbc);
    }

    /**
     * Thin algorithm/AI capability catalogs (classpath YAML) — 薄算法/AI 能力目录（classpath YAML）。
     */
    @Bean
    CapabilityCatalog capabilityCatalog() {
        return new CapabilityCatalog();
    }

    /**
     * AI completion client: stub (default) or http (needs env secrets; transport gated).
     * AI 补全客户端：默认 stub；http 需环境密钥（传输层未全开）。
     */
    @Bean
    ModelCompletionClient modelCompletionClient(
            @Value("${platform.ai.completion.mode:stub}") String mode,
            @Value("${platform.ai.completion.http.base-url:}") String httpBaseUrl,
            @Value("${platform.ai.completion.http.api-key:}") String httpApiKey,
            @Value("${platform.ai.completion.http.model:}") String httpModel) {
        return ModelCompletionClients.forMode(mode, httpBaseUrl, httpApiKey, httpModel);
    }

    @Bean
    CapabilityRunner capabilityRunner(
            CapabilityCatalog capabilityCatalog, ModelCompletionClient modelCompletionClient) {
        return new CapabilityRunner(capabilityCatalog, modelCompletionClient);
    }

    /** AI write-back confirm gate (in-memory tickets) — AI 写回确认门闩（进程内票）。 */
    @Bean
    AiWriteConfirmGate aiWriteConfirmGate(Clock clock) {
        return new InMemoryAiWriteConfirmGate(clock);
    }

    /** Default AI write-back sink: noop (no DB) — 默认写回落点：noop 不落库。 */
    @Bean
    AiWriteBackSink aiWriteBackSink() {
        return new NoOpAiWriteBackSink();
    }

    /**
     * Run declared form domainAction through Service / Config / entity / capability catalogs — 经服务 / 配置 / 实体 / 能力目录执行表单领域动作。
     */
    @Bean
    FormDomainActionRunner formDomainActionRunner(
            ServiceCatalog serviceCatalog,
            ConfigCatalog configCatalog,
            EffectiveDeclarationService effectiveDeclarationService,
            GenericEntityStore genericEntityStore,
            CapabilityRunner capabilityRunner,
            TenantGuard tenantGuard,
            OperatorTenantAccess operatorTenantAccess) {
        return new FormDomainActionRunner(
                serviceCatalog,
                configCatalog,
                effectiveDeclarationService,
                genericEntityStore,
                capabilityRunner,
                tenantGuard,
                operatorTenantAccess);
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

    /**
     * Read-only org_unit tree and memberships — 只读组织树与成员关系。
     */
    /**
     * O2 Organization ontology store (alongside legacy org_unit) —
     * O2 Organization 本体存储（与旧 org_unit 并存）。
     */
    @Bean
    JdbcOrganizationStore jdbcOrganizationStore(JdbcTemplate jdbc) {
        return new JdbcOrganizationStore(jdbc);
    }

    /**
     * O8-1 organization scope from Membership + CONTAINS (no map / JdbcOrgDirectory) —
     * O8-1 组织范围（Membership + CONTAINS；不经 map / 旧目录）。
     */
    @Bean
    OrganizationScopeResolver organizationScopeResolver(JdbcOrganizationStore jdbcOrganizationStore) {
        return new OrganizationScopeResolver(jdbcOrganizationStore);
    }

    /**
     * O8-4: migration / legacy-adapter only — not injected into OrganizationApiEndpoint.
     * Flyway V24 and explicit upgrade call {@code backfillAll}/{@code backfillTenant};
     * {@code JdbcOrgDirectory} uses write-through helpers. Formal API/Policy/scope do not.
     * O8-4：仅迁移与旧目录；正式 Organization API 不注入。
     */
    @Bean
    OrganizationOntologyBackfill organizationOntologyBackfill(
            JdbcTemplate jdbc, JdbcOrganizationStore jdbcOrganizationStore) {
        return new OrganizationOntologyBackfill(jdbc, jdbcOrganizationStore);
    }

    /**
     * O8-3 legacy-only: {@code /api/v1/org/**} adapter (JdbcOrgDirectory).
     * New Organization API / Policy / OrganizationScopeResolver must not use this bean.
     * 仅旧 API；新组织 API / 策略 / 范围解析器不得依赖。
     */
    @Bean
    JdbcOrgDirectory jdbcOrgDirectory(JdbcTemplate jdbc, JdbcOrganizationStore jdbcOrganizationStore) {
        return new JdbcOrgDirectory(jdbc, jdbcOrganizationStore);
    }

    /**
     * Policy engine port — AuthZ-1d: {@code platform.authz.engine=cedar|sql} (default {@code cedar}).
     * Cedar unavailable with engine=cedar → fail-closed at startup. SQL remains selectable.
     * 策略引擎：默认 Cedar；可用 sql 回退；选 cedar 但 FFI 不可用则启动失败。
     */
    @Bean
    PolicyEngine policyEngine(
            TenantGuard tenantGuard,
            OperatorTenantAccess operatorTenantAccess,
            @Value("${platform.authz.engine:cedar}") String authzEngine) {
        return PolicyEngineFactory.create(authzEngine, tenantGuard, operatorTenantAccess);
    }

    /**
     * Tenant-scoped declaration draft revisions — 租户隔离的声明草稿修订。
     */
    @Bean
    JdbcDeclarationStore jdbcDeclarationStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcDeclarationStore(jdbc, clock);
    }

    @Bean
    JdbcDeclarationPromoteApprovalStore jdbcDeclarationPromoteApprovalStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcDeclarationPromoteApprovalStore(jdbc, clock);
    }

    /**
     * Declaration schema migration queue store — 声明 schema 迁移队列存取。
     */
    @Bean
    JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcDeclarationMigrationStore(jdbc, clock);
    }

    /**
     * Apply REVIEWED entity DDL (fail-closed) — 执行 REVIEWED 实体 DDL（失败关闭）。
     */
    @Bean
    DeclarationMigrationApplyService declarationMigrationApplyService(
            JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore,
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate) {
        return new DeclarationMigrationApplyService(
                jdbcDeclarationMigrationStore, jdbc, transactionTemplate);
    }

    /**
     * Auto-enqueue ALTER ADD / CREATE on entity draft save (RT-4) —
     * 实体草稿保存时自动入队 ALTER ADD / CREATE（RT-4）。
     */
    @Bean
    DeclarationMigrationAutoEnqueueService declarationMigrationAutoEnqueueService(
            JdbcDeclarationStore jdbcDeclarationStore,
            JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore,
            EntityCatalog entityCatalog) {
        return new DeclarationMigrationAutoEnqueueService(
                jdbcDeclarationStore, jdbcDeclarationMigrationStore, entityCatalog);
    }

    /**
     * In-platform declaration git working tree (local commit only) — 平台内声明 git 工作树（仅本地提交）。
     */
    @Bean
    InternalDeclarationGit internalDeclarationGit() {
        return new InternalDeclarationGit();
    }

    /**
     * Promote drafts into internal git + PROMOTED + audit row — 草稿晋升进内部 git 并翻 PROMOTED、写审计。
     */
    @Bean
    DeclarationPromoteService declarationPromoteService(
            JdbcDeclarationStore jdbcDeclarationStore,
            JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore,
            JdbcDeclarationPromoteApprovalStore jdbcDeclarationPromoteApprovalStore,
            InternalDeclarationGit internalDeclarationGit,
            TransactionTemplate transactionTemplate,
            @Value("${platform.declaration.git.dir:./data/declaration-git}") String gitDir) {
        return new DeclarationPromoteService(
                jdbcDeclarationStore,
                jdbcDeclarationMigrationStore,
                jdbcDeclarationPromoteApprovalStore,
                internalDeclarationGit,
                Path.of(gitDir),
                transactionTemplate);
    }

    /**
     * Tenant draft overlay over classpath catalogs — 租户草稿覆盖 classpath 目录。
     */
    @Bean
    EffectiveDeclarationService effectiveDeclarationService(
            JdbcDeclarationStore jdbcDeclarationStore,
            JdbcDeclarationMigrationStore jdbcDeclarationMigrationStore,
            EntityCatalog entityCatalog,
            FormCatalog formCatalog,
            PageCatalog pageCatalog) {
        return new EffectiveDeclarationService(
                jdbcDeclarationStore,
                jdbcDeclarationMigrationStore,
                entityCatalog,
                formCatalog,
                pageCatalog);
    }


    @Bean
    FormSubmissionStore formSubmissionStore(JdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper) {
        return new JdbcFormSubmissionStore(jdbc, clock, objectMapper);
    }
}
