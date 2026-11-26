package com.subjex.platform.app;

import tools.jackson.databind.ObjectMapper;
import com.subjex.platform.app.connection.ConfiguredConnection;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreaker;
import com.subjex.platform.contract.delivery.DeliveryCircuitBreakerPort;
import com.subjex.platform.app.delivery.JdbcDeliveryCircuitBreakerPort;
import com.subjex.platform.app.delivery.OutboxSocketPublisher;
import com.subjex.platform.contract.delivery.DeliveryPort;
import com.subjex.platform.contract.inbox.InboxGroupPort;
import com.subjex.platform.contract.inbox.InboxPort;
import com.subjex.platform.contract.notification.NotificationPort;
import com.subjex.platform.contract.consent.ConsentPort;
import com.subjex.platform.contract.payment.PaymentProviderIds;
import com.subjex.platform.contract.notification.NotificationChannel;
import com.subjex.platform.contract.notification.NotificationDispatcher;
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
import com.subjex.platform.app.entity.HybridEntityStore;
import com.subjex.platform.app.entity.AttrsJsonbQueries;
import com.subjex.platform.app.entity.JdbcHybridEntityStore;
import com.subjex.platform.app.search.EntityRecordSearchStore;
import com.subjex.platform.app.search.JdbcEntityRecordSearchStore;
import com.subjex.platform.app.entity.AuthorizedObjectRegistrar;
import com.subjex.platform.app.entity.ChunkedBlobUploadStore;
import com.subjex.platform.app.identity.JdbcOauthAuthCodeStore;
import com.subjex.platform.app.identity.JdbcOauthClientStore;
import com.subjex.platform.app.identity.JdbcOauthConsentChallengeStore;
import com.subjex.platform.app.identity.JdbcOauthConsentStore;
import com.subjex.platform.app.identity.JdbcOauthScopeCatalog;
import com.subjex.platform.app.identity.OpJwtService;
import com.subjex.platform.app.identity.OpProperties;
import com.subjex.platform.app.identity.OpSigningKeyService;
import com.subjex.platform.app.entity.EntityBlobStore;
import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.entity.LocalDirChunkedBlobUploadStore;
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
import com.subjex.platform.app.uiauthz.UiPermissionDraftService;
import com.subjex.platform.app.uiauthz.UiPermissionManifestCatalog;
import com.subjex.platform.app.capability.CapabilityRunner;
import com.subjex.platform.app.capability.InMemoryAiWriteConfirmGate;
import com.subjex.platform.app.capability.ModelCompletionClient;
import com.subjex.platform.app.capability.ModelCompletionClients;
import com.subjex.platform.app.capability.NoOpAiWriteBackSink;
import com.subjex.platform.app.form.DomainActionHandler;
import com.subjex.platform.app.form.FormDomainActionRunner;
import com.subjex.platform.app.form.FormSideEffectRunner;
import com.subjex.platform.app.form.FormSubmissionStore;
import com.subjex.platform.app.form.JdbcFormSubmissionStore;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.inbox.InboxFanoutWorker;
import com.subjex.platform.app.inbox.InboxService;
import com.subjex.platform.app.inbox.InboxAttachmentStagingStore;
import com.subjex.platform.app.inbox.InboxGroupService;
import com.subjex.platform.app.inbox.InboxOrgBroadcastExpander;
import com.subjex.platform.app.inbox.JdbcInboxGroupPort;
import com.subjex.platform.app.inbox.JdbcInboxPort;
import com.subjex.platform.app.notification.JdbcNotificationPort;
import com.subjex.platform.app.notification.JdbcNotificationSendQuery;
import com.subjex.platform.app.notification.OperatorNotificationService;
import com.subjex.platform.app.consent.JdbcConsentPort;
import com.subjex.platform.app.consent.OperatorConsentService;
import com.subjex.platform.app.exporthook.ExportApprovalGate;
import com.subjex.platform.app.exporthook.ExportRequestService;
import com.subjex.platform.contract.exporthook.SubjectRecordExporter;
import com.subjex.platform.app.workflow.WorkflowExportHook;
import com.subjex.platform.app.exporthook.GenericSubjectRecordExporter;
import com.subjex.platform.app.exporthook.ExportGenerationService;
import com.subjex.platform.app.exporthook.ExportExpiryService;
import com.subjex.platform.app.exporthook.JdbcExportRequestStore;
import com.subjex.platform.app.payment.JdbcPaymentPort;
import com.subjex.platform.app.payment.OperatorPaymentService;
import com.subjex.platform.app.payment.RecordingPaymentSink;
import com.subjex.platform.app.payment.JdkPaymentHttpClient;
import com.subjex.platform.app.payment.PaymentHttpClient;
import com.subjex.platform.app.payment.PaymentProviderAdapterFactory;
import com.subjex.platform.app.payment.PaymentProviderSettings;
import com.subjex.platform.app.notification.JdbcNotificationDispatcher;
import com.subjex.platform.app.notification.JdkNotificationHttpClient;
import com.subjex.platform.app.notification.NotificationChannelAdapterFactory;
import com.subjex.platform.app.notification.NotificationChannelSettings;
import com.subjex.platform.app.notification.NotificationHttpClient;
import com.subjex.platform.app.notification.RecordingNotificationSink;
import com.subjex.platform.app.questionnaire.JdbcQuestionnaireAssignmentStore;
import com.subjex.platform.app.questionnaire.JdbcQuestionnairePort;
import com.subjex.platform.app.questionnaire.QuestionnaireAssignmentService;
import com.subjex.platform.app.questionnaire.QuestionnaireAssignmentStore;
import com.subjex.platform.app.questionnaire.QuestionnaireAssignmentVisibility;
import com.subjex.platform.app.questionnaire.QuestionnaireService;
import com.subjex.platform.contract.questionnaire.QuestionnairePort;
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
import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserDirectoryService;
import com.subjex.platform.app.resourceevent.JdbcResourceEventStore;
import com.subjex.platform.app.resourceevent.ResourceEventService;
import com.subjex.platform.app.identity.enduser.EndUserShellService;
import com.subjex.platform.app.identity.enduser.EndUserObjectService;
import com.subjex.platform.app.identity.enduser.EndUserProfileStore;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.identity.enduser.JdbcEndUserProfileStore;
import com.subjex.platform.app.identity.enduser.JdbcEndUserTokenStore;
import com.subjex.platform.app.identity.enduser.EndUserPhoneAuthService;
import com.subjex.platform.app.identity.enduser.AdapterEndUserWeChatAssertion;
import com.subjex.platform.app.identity.enduser.DevEndUserWeChatAssertion;
import com.subjex.platform.app.identity.enduser.EndUserWeChatAssertion;
import com.subjex.platform.app.identity.enduser.EndUserWeChatAuthService;
import com.subjex.platform.app.identity.enduser.FailClosedEndUserWeChatAssertion;
import com.subjex.platform.app.identity.enduser.JdbcEndUserLoginLockout;
import com.subjex.platform.app.identity.enduser.JdkVerificationCodeHttpClient;
import com.subjex.platform.app.identity.enduser.MemoryVerificationCodePort;
import com.subjex.platform.app.identity.enduser.VerificationCodeHttpClient;
import com.subjex.platform.app.identity.enduser.VerificationCodePort;
import com.subjex.platform.app.identity.enduser.VerificationCodeSender;
import com.subjex.platform.app.identity.enduser.VerificationCodeSenderFactory;
import com.subjex.platform.app.security.ProductionProfileGuard;
import com.subjex.platform.app.security.JdbcOperatorLoginLockout;
import com.subjex.platform.app.security.JdbcOperatorMfaStore;
import com.subjex.platform.app.security.AesGcmSecretCipher;
import com.subjex.platform.app.tenant.TenantHostService;
import com.subjex.platform.app.tenant.TenantLifecycleService;
import com.subjex.platform.app.workflow.JdbcWorkflowStore;
import com.subjex.platform.app.workflow.WorkflowCatalog;
import com.subjex.platform.app.workflow.WorkflowOrgApproverGate;
import com.subjex.platform.app.workflow.RepairTicketWorkflowInboxHook;
import com.subjex.platform.app.workflow.WorkflowService;
import com.subjex.platform.app.tenant.TenantQuotaService;
import com.subjex.platform.contract.delivery.SecretPlaceholderGuard;
import com.subjex.platform.app.security.OidcProperties;
import com.subjex.platform.app.security.JdbcOidcLoginStore;
import com.subjex.platform.app.security.JdbcOperatorIdpLinkStore;
import com.subjex.platform.app.security.OidcTokenClient;
import com.subjex.platform.app.security.HttpOidcTokenClient;
import com.subjex.platform.app.security.OperatorOidcService;
import com.subjex.platform.app.idp.GenericOidcIdpAdapter;
import com.subjex.platform.app.idp.HttpWeChatApiClient;
import com.subjex.platform.app.idp.IdpAdapter;
import com.subjex.platform.app.idp.IdpAdapterRegistry;
import com.subjex.platform.app.idp.IdpLinkProofStore;
import com.subjex.platform.app.idp.IdpLoginOrchestrator;
import com.subjex.platform.app.idp.WeChatApiClient;
import com.subjex.platform.app.idp.WeChatIdpAdapter;
import com.subjex.platform.app.idp.WeChatProperties;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorTenantAccess;
import com.subjex.platform.app.security.PolicyEngine;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.storage.ObjectStorageFactory;
import com.subjex.platform.app.storage.ObjectAccessAudit;
import com.subjex.platform.app.storage.ObjectGrantStore;
import com.subjex.platform.app.storage.ObjectUploadLimits;
import com.subjex.platform.app.storage.ObjectAccessAuthorizer;
import com.subjex.platform.app.storage.LocalDownloadSignature;
import com.subjex.platform.app.storage.LocalHmacDownloadIssuer;
import com.subjex.platform.app.storage.ObjectDownloadLinkService;
import com.subjex.platform.app.storage.ObjectDownloadProperties;
import com.subjex.platform.app.storage.ObjectDownloadUrlIssuer;
import com.subjex.platform.app.storage.S3ObjectStorage;
import com.subjex.platform.app.storage.S3PresignedDownloadIssuer;
import com.subjex.platform.app.storage.JdbcObjectGrantStore;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.audit.AuditSink;
import com.subjex.platform.app.admin.SinkingAuditPort;
import com.subjex.platform.app.admin.NdjsonAuditSink;
import com.subjex.platform.app.admin.AuditExportService;
import com.subjex.platform.app.admin.LoadDashboardService;
import com.subjex.platform.app.admin.RateLimitCircuitObservationService;
import com.subjex.platform.app.admin.SessionListService;
import com.subjex.platform.app.config.ConfigCatalog;
import com.subjex.platform.app.flags.FeatureFlagService;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.availability.ApplicationAvailability;
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

    /**
     * Feature flags resolver (config namespace flags) — 功能开关解析（flags 命名空间）。
     * Injects {@link ConfigCatalog} (the sole {@link com.subjex.platform.contract.config.ConfigCenterPort}) to avoid a duplicate bean alias.
     * 注入唯一的 ConfigCenterPort 实现 ConfigCatalog，避免别名 bean 与实现类型双注册。
     */
    @Bean
    FeatureFlagService featureFlagService(ConfigCatalog configCatalog) {
        return new FeatureFlagService(configCatalog);
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

    /**
     * Object storage: {@code local} (base / laptop) or {@code s3} (prod default, MinIO-compatible) —
     * 对象存储：本机/基础配置为 local；生产默认 s3（MinIO 兼容）。
     * Production refuses local disk and fails fast when S3 settings are missing.
     * 生产配置拒绝本地盘；S3 配置缺失时启动失败，不回退到本地目录。
     */
    @Bean
    ObjectStorage objectStorage(
            @Value("${platform.storage.type:local}") String type,
            @Value("${platform.storage.directory:object-store}") String directory,
            @Value("${platform.storage.s3.endpoint:}") String s3Endpoint,
            @Value("${platform.storage.s3.region:us-east-1}") String s3Region,
            @Value("${platform.storage.s3.bucket:}") String s3Bucket,
            @Value("${platform.storage.s3.access-key:}") String s3AccessKey,
            @Value("${platform.storage.s3.secret-key:}") String s3SecretKey,
            @Value("${platform.storage.s3.path-style-access:true}") boolean s3PathStyle,
            Environment environment) {
        boolean productionProfile = environment.matchesProfiles("prod") || environment.matchesProfiles("production");
        return ObjectStorageFactory.create(
                type,
                directory,
                s3Endpoint,
                s3Region,
                s3Bucket,
                s3AccessKey,
                s3SecretKey,
                s3PathStyle,
                productionProfile);
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
    AuditSink auditSink(
            @Value("${platform.audit.sink.enabled:false}") boolean enabled,
            @Value("${platform.audit.sink.type:stdout}") String type,
            @Value("${platform.audit.sink.file:}") String file) {
        java.nio.file.Path path = (file == null || file.isBlank()) ? null : java.nio.file.Path.of(file);
        return new NdjsonAuditSink(enabled, type, path);
    }

    @Bean
    AuditPort auditPort(JdbcTemplate jdbc, AuditSink auditSink) {
        return new SinkingAuditPort(new JdbcAuditPort(jdbc), auditSink);
    }

    @Bean
    AuditExportService auditExportService(
            JdbcAdminReader jdbcAdminReader,
            OperatorTenantAccess operatorTenantAccess,
            AuditPort auditPort,
            Clock clock) {
        return new AuditExportService(jdbcAdminReader, operatorTenantAccess, auditPort, clock);
    }

    /**
     * Current-login list hung on existing refresh-token stores (no new session product) —
     * 当前登录列表挂在已有刷新令牌存储上（不另做一套会话产品）。
     */
    @Bean
    SessionListService sessionListService(
            JdbcTemplate jdbc,
            JdbcOperatorTokenStore operatorTokenStore,
            JdbcEndUserTokenStore endUserTokenStore,
            OperatorTenantAccess operatorTenantAccess,
            OperatorActionAudit operatorActionAudit,
            Clock clock) {
        return new SessionListService(
                jdbc, operatorTokenStore, endUserTokenStore, operatorTenantAccess, operatorActionAudit, clock);
    }

    /**
     * Operator directory of existing end users in a granted tenant (read-only).
     * 操作员查阅已授权租户内已有终端用户（只读）。
     */
    @Bean
    EndUserDirectoryService endUserDirectoryService(
            JdbcTemplate jdbc, OperatorTenantAccess operatorTenantAccess) {
        return new EndUserDirectoryService(jdbc, operatorTenantAccess);
    }

    /**
     * Generic share/open event store and operator summary (not a marketing suite).
     * 通用分享/打开事件存储与操作员汇总（不是营销套件）。
     */
    @Bean
    JdbcResourceEventStore jdbcResourceEventStore(JdbcTemplate jdbc) {
        return new JdbcResourceEventStore(jdbc);
    }

    @Bean
    ResourceEventService resourceEventService(
            JdbcTemplate jdbc,
            JdbcResourceEventStore jdbcResourceEventStore,
            OperatorTenantAccess operatorTenantAccess,
            RateLimitPort rateLimitPort,
            Clock clock) {
        return new ResourceEventService(
                jdbc, jdbcResourceEventStore, operatorTenantAccess, rateLimitPort, clock);
    }

    /**
     * In-console load snapshot from already-produced Micrometer meters (no Grafana product) —
     * 控制台负载快照来自已有 Micrometer 指标（不是 Grafana 产品）。
     */
    @Bean
    LoadDashboardService loadDashboardService(
            MeterRegistry meterRegistry, ApplicationAvailability applicationAvailability, Clock clock) {
        return new LoadDashboardService(meterRegistry, applicationAvailability, clock);
    }

    /**
     * Operator observation of current rate-limit windows and delivery-breaker state (existing beans).
     * 操作员观察当前限额时间窗与投递熔断开合（挂在已有实现上）。
     */
    @Bean
    RateLimitCircuitObservationService rateLimitCircuitObservationService(
            RateLimitPort rateLimitPort,
            DeliveryCircuitBreakerPort deliveryCircuitBreakerPort,
            @Value("${platform.rate-limit.backend:process}") String rateLimitBackend,
            @Value("${platform.delivery.circuit-breaker.backend:process}") String circuitBackend,
            @Value("${platform.delivery.circuit-breaker.destination-key:outbox}") String destinationKey,
            Clock clock) {
        return new RateLimitCircuitObservationService(
                rateLimitPort,
                deliveryCircuitBreakerPort,
                rateLimitBackend,
                circuitBackend,
                destinationKey,
                clock);
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

    /**
     * End-user account + token stores (WP2-1/2). Not the operator token store.
     * 终端账号与令牌仓库（WP2-1/2）。不是操作员令牌仓库。
     */
    @Bean
    JdbcEndUserAccountStore endUserAccountStore(
            JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock) {
        return new JdbcEndUserAccountStore(jdbc, transactionTemplate, clock);
    }

    /**
     * Account-level profile fields (avatar object id + verified/pending email).
     * 账号级资料字段（头像对象标识与已验证/待验证邮箱）。
     */
    @Bean
    EndUserProfileStore endUserProfileStore(
            JdbcTemplate jdbc, TransactionTemplate transactionTemplate, Clock clock) {
        return new JdbcEndUserProfileStore(jdbc, transactionTemplate, clock);
    }

    /**
     * End-user profile / inbox / questionnaire shell (ADR 0019).
     * 终端资料（含头像与须验证邮箱）、收件箱与认证问卷外壳；附件按本人收件直接下载；不拓宽操作员路由。
     */
    @Bean
    EndUserShellService endUserShellService(
            EndUserAccountStore endUserAccountStore,
            InboxPort inbox,
            QuestionnairePort questionnaires,
            AuditPort auditPort,
            ObjectStorage objectStorage,
            EndUserProfileStore endUserProfileStore,
            ObjectGrantStore objectGrantStore,
            ObjectDownloadLinkService objectDownloadLinkService,
            VerificationCodePort endUserVerificationCodePort,
            QuestionnaireAssignmentVisibility questionnaireAssignmentVisibility,
            Clock clock) {
        return new EndUserShellService(
                endUserAccountStore,
                inbox,
                questionnaires,
                auditPort,
                objectStorage,
                endUserProfileStore,
                objectGrantStore,
                objectDownloadLinkService,
                endUserVerificationCodePort,
                questionnaireAssignmentVisibility,
                clock);
    }

    /**
     * WP8-1 purpose-consent ledger (ADR 0020). Grant/withdraw audit via AuditPort; no outbox in this slice.
     * 目的同意台账；授予/撤销经 AuditPort 审计；本切片不写出箱。
     */
    @Bean
    ConsentPort consentPort(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            AuditPort auditPort) {
        return new JdbcConsentPort(jdbc, transactionTemplate, clock, auditPort);
    }

    /**
     * Operator read-only consent query for the console list. No grant or withdraw.
     * 操作员只读同意查询，供控制台列表使用；不能授予或撤回。
     */
    @Bean
    OperatorConsentService operatorConsentService(
            ConsentPort consentPort,
            OperatorTenantAccess operatorTenantAccess,
            PolicyEngine policyEngine) {
        return new OperatorConsentService(consentPort, operatorTenantAccess, policyEngine);
    }

    /**
     * WP8-2 export request store, approval gate, and service (ADR 0020). No artifact generation, no outbox.
     * 导出申请存储、审批门禁与服务；不生成产物、不写出箱。
     */
    @Bean
    JdbcExportRequestStore jdbcExportRequestStore(JdbcTemplate jdbc) {
        return new JdbcExportRequestStore(jdbc);
    }

    @Bean
    ExportApprovalGate exportApprovalGate(JdbcExportRequestStore jdbcExportRequestStore, AuditPort auditPort, Clock clock) {
        return new ExportApprovalGate(jdbcExportRequestStore, auditPort, clock);
    }

    @Bean
    ExportRequestService exportRequestService(
            JdbcTemplate jdbc,
            JdbcExportRequestStore jdbcExportRequestStore,
            WorkflowService workflowService,
            EndUserAccountStore endUserAccountStore,
            OperatorTenantAccess operatorTenantAccess,
            PolicyEngine policyEngine,
            AuditPort auditPort,
            Clock clock) {
        return new ExportRequestService(
                jdbc,
                jdbcExportRequestStore,
                workflowService,
                endUserAccountStore,
                operatorTenantAccess,
                policyEngine,
                auditPort,
                clock);
    }

    @Bean
    WorkflowExportHook workflowExportHook(WorkflowService workflowService) {
        return new WorkflowExportHook(workflowService);
    }

    @Bean
    ExportExpiryService exportExpiryService(
            JdbcExportRequestStore jdbcExportRequestStore, Clock clock, WorkflowExportHook workflowExportHook) {
        return new ExportExpiryService(jdbcExportRequestStore, clock, workflowExportHook);
    }

    @Bean
    GenericSubjectRecordExporter genericSubjectRecordExporter(
            JdbcTemplate jdbc,
            ConsentPort consentPort,
            InboxPort inboxPort,
            QuestionnairePort questionnairePort,
            ObjectGrantStore objectGrantStore,
            Clock clock) {
        return new GenericSubjectRecordExporter(
                jdbc, consentPort, inboxPort, questionnairePort, objectGrantStore, clock);
    }

    @Bean
    ExportGenerationService exportGenerationService(
            JdbcExportRequestStore jdbcExportRequestStore,
            ExportApprovalGate exportApprovalGate,
            ExportExpiryService exportExpiryService,
            List<SubjectRecordExporter> subjectRecordExporters,
            ObjectStorage objectStorage,
            ObjectGrantStore objectGrantStore,
            ObjectDownloadLinkService objectDownloadLinkService,
            WorkflowService workflowService,
            EndUserAccountStore endUserAccountStore,
            OperatorTenantAccess operatorTenantAccess,
            PolicyEngine policyEngine,
            AuditPort auditPort,
            Clock clock,
            @Value("${platform.export.artifact-ttl:P7D}") Duration artifactTtl,
            TransactionTemplate transactionTemplate) {
        return new ExportGenerationService(
                jdbcExportRequestStore,
                exportApprovalGate,
                exportExpiryService,
                subjectRecordExporters,
                objectStorage,
                objectGrantStore,
                objectDownloadLinkService,
                workflowService,
                endUserAccountStore,
                operatorTenantAccess,
                policyEngine,
                auditPort,
                clock,
                artifactTtl,
                transactionTemplate);
    }

    @Bean
    JdbcEndUserTokenStore endUserTokenStore(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            EndUserAccountStore endUserAccountStore,
            Clock clock,
            @Value("${platform.end-user.auth.access-token-ttl:PT30M}") Duration accessTokenTtl,
            @Value("${platform.end-user.auth.refresh-token-ttl:PT8H}") Duration refreshTokenTtl) {
        return new JdbcEndUserTokenStore(
                jdbc, transactionTemplate, endUserAccountStore, clock, accessTokenTtl, refreshTokenTtl);
    }

    /**
     * JDK transport for the vendor-shaped SMS OTP template. Selection still requires explicit config.
     * 厂商形短信验证码模板的 JDK 传输。是否启用仍须显式配置，仅存在本 Bean 不会外呼。
     */
    @Bean
    VerificationCodeHttpClient endUserVerificationCodeHttpClient() {
        return new JdkVerificationCodeHttpClient();
    }

    /**
     * End-user phone verification-code sender (WP2-3). Production stays fail-closed unless
     * {@code sender=http} is explicit and complete. {@code sender=dev} is refused in production.
     * Incomplete vendor config never falls back to the dev sender. Codes are never logged.
     * 终端手机验证码发送器。生产默认失败关闭；仅在显式且配齐 {@code sender=http} 时启用模板。
     * 生产禁止 {@code sender=dev}。厂商配置不完整绝不回退到开发发送器。验证码不写入日志。
     */
    @Bean
    VerificationCodeSender endUserVerificationCodeSender(
            Environment environment,
            VerificationCodeHttpClient endUserVerificationCodeHttpClient,
            @Value("${platform.end-user.auth.verification-code.reveal-in-response:false}") boolean revealInResponse,
            @Value("${platform.end-user.auth.verification-code.sender:}") String sender,
            @Value("${platform.end-user.auth.verification-code.http.base-url:}") String httpBaseUrl,
            @Value("${platform.end-user.auth.verification-code.http.api-key:}") String httpApiKey,
            @Value("${platform.end-user.auth.verification-code.http.from:}") String httpFrom) {
        return VerificationCodeSenderFactory.create(
                environment.getActiveProfiles(),
                revealInResponse,
                sender,
                httpBaseUrl,
                httpApiKey,
                httpFrom,
                endUserVerificationCodeHttpClient);
    }

    @Bean
    MemoryVerificationCodePort endUserVerificationCodePort(
            Clock clock,
            VerificationCodeSender endUserVerificationCodeSender,
            @Value("${platform.end-user.auth.verification-code.ttl:PT5M}") Duration ttl,
            @Value("${platform.end-user.auth.verification-code.reveal-in-response:false}") boolean revealInResponse) {
        return new MemoryVerificationCodePort(clock, ttl, revealInResponse, endUserVerificationCodeSender);
    }

    @Bean
    JdbcEndUserLoginLockout endUserLoginLockout(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            @Value("${platform.end-user.auth.lockout-max-failures:5}") int maxFailures,
            @Value("${platform.end-user.auth.lockout-duration:PT15M}") Duration lockoutDuration) {
        return new JdbcEndUserLoginLockout(
                jdbc, transactionTemplate, clock, maxFailures, lockoutDuration);
    }

    @Bean
    EndUserPhoneAuthService endUserPhoneAuthService(
            EndUserAccountStore endUserAccountStore,
            JdbcEndUserTokenStore endUserTokenStore,
            VerificationCodePort endUserVerificationCodePort,
            JdbcEndUserLoginLockout endUserLoginLockout,
            JdbcTemplate jdbc,
            AuditPort auditPort,
            Clock clock) {
        return new EndUserPhoneAuthService(
                endUserAccountStore,
                endUserTokenStore,
                endUserVerificationCodePort,
                endUserLoginLockout,
                jdbc,
                auditPort,
                clock);
    }

    /**
     * End-user WeChat mini-program assertion (WP2-4). Dev stub is local-only; prod without mini
     * config is fail-closed and never auto-provisions.
     * 终端微信小程序断言。开发桩仅限本机；生产未配置则失败关闭，绝不自动开户。
     */
    @Bean
    EndUserWeChatAssertion endUserWeChatAssertion(
            Environment environment,
            WeChatIdpAdapter weChatIdpAdapter,
            WeChatProperties weChatProperties,
            @Value("${platform.end-user.auth.wechat.dev-stub:false}") boolean devStub) {
        boolean production = ProductionProfileGuard.isProductionProfile(environment.getActiveProfiles());
        if (production && devStub) {
            throw new IllegalStateException(
                    "platform.end-user.auth.wechat.dev-stub must stay false under prod/production");
        }
        if (devStub) {
            return new DevEndUserWeChatAssertion();
        }
        if (production && !weChatProperties.miniReady()) {
            return new FailClosedEndUserWeChatAssertion();
        }
        return new AdapterEndUserWeChatAssertion(weChatIdpAdapter, weChatProperties);
    }

    @Bean
    EndUserWeChatAuthService endUserWeChatAuthService(
            EndUserAccountStore endUserAccountStore,
            JdbcEndUserTokenStore endUserTokenStore,
            EndUserWeChatAssertion endUserWeChatAssertion,
            JdbcEndUserLoginLockout endUserLoginLockout,
            JdbcTemplate jdbc) {
        return new EndUserWeChatAuthService(
                endUserAccountStore,
                endUserTokenStore,
                endUserWeChatAssertion,
                endUserLoginLockout,
                jdbc);
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
    WeChatProperties weChatProperties(
            @Value("${platform.idp.wechat.enabled:false}") boolean enabled,
            @Value("${platform.idp.wechat.app-id:}") String appId,
            @Value("${platform.idp.wechat.app-secret:}") String appSecret,
            @Value("${platform.idp.wechat.redirect-uri:}") String redirectUri) {
        return new WeChatProperties(enabled, appId, appSecret, redirectUri);
    }

    @Bean
    WeChatApiClient weChatApiClient(WeChatProperties weChatProperties, ObjectMapper objectMapper) {
        return new HttpWeChatApiClient(weChatProperties, objectMapper);
    }

    @Bean
    GenericOidcIdpAdapter genericOidcIdpAdapter(
            OidcProperties oidcProperties, JdbcOidcLoginStore oidcLoginStore, OidcTokenClient oidcTokenClient) {
        return new GenericOidcIdpAdapter(oidcProperties, oidcLoginStore, oidcTokenClient);
    }

    @Bean
    WeChatIdpAdapter weChatIdpAdapter(
            WeChatProperties weChatProperties, WeChatApiClient weChatApiClient, JdbcOidcLoginStore oidcLoginStore) {
        return new WeChatIdpAdapter(weChatProperties, weChatApiClient, oidcLoginStore);
    }

    @Bean
    IdpAdapterRegistry idpAdapterRegistry(GenericOidcIdpAdapter genericOidc, WeChatIdpAdapter weChat) {
        return new IdpAdapterRegistry(java.util.List.<IdpAdapter>of(genericOidc, weChat));
    }

    @Bean
    IdpLinkProofStore idpLinkProofStore(Clock clock) {
        return new IdpLinkProofStore(clock);
    }

    @Bean
    IdpLoginOrchestrator idpLoginOrchestrator(
            IdpAdapterRegistry idpAdapterRegistry,
            JdbcOperatorIdpLinkStore operatorIdpLinkStore,
            JdbcOperatorDirectory operatorDirectory,
            JdbcOperatorTokenStore operatorTokenStore) {
        return new IdpLoginOrchestrator(
                idpAdapterRegistry, operatorIdpLinkStore, operatorDirectory, operatorTokenStore);
    }

    @Bean
    OperatorOidcService operatorOidcService(
            OidcProperties oidcProperties,
            GenericOidcIdpAdapter genericOidcIdpAdapter,
            IdpLoginOrchestrator idpLoginOrchestrator) {
        return new OperatorOidcService(oidcProperties, genericOidcIdpAdapter, idpLoginOrchestrator);
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
    TenantLifecycleService tenantLifecycleService(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            JdbcTenantAdmin jdbcTenantAdmin,
            ObjectStorage objectStorage,
            OperatorActionAudit operatorActionAudit,
            ObjectMapper objectMapper,
            Clock clock) {
        return new TenantLifecycleService(
                jdbc,
                transactionTemplate,
                jdbcTenantAdmin,
                objectStorage,
                operatorActionAudit,
                objectMapper,
                clock);
    }

    /**
     * Custom tenant hostnames (Host routing). Reserved list is the platform/console API names.
     * 租户自定义主机名（按 Host 路由）。保留名单是平台/控制台接口用的名字。
     */
    @Bean
    TenantHostService tenantHostService(
            JdbcTemplate jdbc,
            JdbcTenantAdmin jdbcTenantAdmin,
            OperatorActionAudit operatorActionAudit,
            Clock clock,
            @Value("${platform.hosts.reserved:localhost,127.0.0.1,::1}") String reservedHosts) {
        return new TenantHostService(jdbc, jdbcTenantAdmin, operatorActionAudit, clock, reservedHosts);
    }




    @Bean
    NotificationPort notificationPort(JdbcTemplate jdbc, ObjectMapper objectMapper, Clock clock) {
        return new JdbcNotificationPort(jdbc, objectMapper, clock);
    }

    /**
     * Operator read-only notification send-log query for the console list. No send, retry, or replay.
     * 操作员只读通知发送记录查询，供控制台列表使用；不能发送、重试或重放。
     */
    @Bean
    JdbcNotificationSendQuery jdbcNotificationSendQuery(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        return new JdbcNotificationSendQuery(jdbc, objectMapper);
    }

    @Bean
    OperatorNotificationService operatorNotificationService(
            JdbcNotificationSendQuery jdbcNotificationSendQuery,
            OperatorTenantAccess operatorTenantAccess,
            PolicyEngine policyEngine) {
        return new OperatorNotificationService(
                jdbcNotificationSendQuery, operatorTenantAccess, policyEngine);
    }

    @Bean
    RecordingPaymentSink paymentAdapterSink() {
        return new RecordingPaymentSink();
    }

    /**
     * JDK transport for the vendor-shaped payment template. Selection still requires explicit config.
     * 厂商形支付模板的 JDK 传输。是否启用仍须显式配置，仅存在本 Bean 不会外呼。
     */
    @Bean
    PaymentHttpClient paymentHttpClient() {
        return new JdkPaymentHttpClient();
    }

    /**
     * Payment adapters from explicit per-provider config. Production stays fail-closed unless
     * {@code adapter=http} is complete. {@code adapter=mock} is refused in production.
     * Incomplete merchant config never falls back to the mock adapter. Bodies and keys are never logged.
     * Reserved {@code WECHAT_PAY} / {@code ALIPAY} names use the same HTTP template, not a vendor SDK.
     * 按各供应商显式配置选择适配器。生产默认失败关闭；仅在配齐 {@code adapter=http} 时启用模板。
     * 生产禁止 {@code adapter=mock}。商户配置不完整绝不回退到模拟适配器。请求体与密钥不写入日志。
     * 预留的微信支付/支付宝标识走同一 HTTP 模板，不是厂商 SDK。
     */
    @Bean
    JdbcPaymentPort paymentPort(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            ObjectMapper objectMapper,
            Environment environment,
            PaymentHttpClient paymentHttpClient,
            RecordingPaymentSink paymentAdapterSink,
            AuditPort auditPort,
            @Value("${platform.payment.mock.adapter:}") String mockAdapter,
            @Value("${platform.payment.wechat-pay.adapter:}") String wechatAdapter,
            @Value("${platform.payment.wechat-pay.http.base-url:}") String wechatBaseUrl,
            @Value("${platform.payment.wechat-pay.http.api-key:}") String wechatApiKey,
            @Value("${platform.payment.wechat-pay.http.merchant-id:}") String wechatMerchantId,
            @Value("${platform.payment.alipay.adapter:}") String alipayAdapter,
            @Value("${platform.payment.alipay.http.base-url:}") String alipayBaseUrl,
            @Value("${platform.payment.alipay.http.api-key:}") String alipayApiKey,
            @Value("${platform.payment.alipay.http.merchant-id:}") String alipayMerchantId) {
        java.util.LinkedHashMap<String, PaymentProviderSettings> settings = new java.util.LinkedHashMap<>();
        settings.put(PaymentProviderIds.MOCK, new PaymentProviderSettings(mockAdapter, "", "", ""));
        settings.put(
                PaymentProviderIds.WECHAT_PAY,
                new PaymentProviderSettings(wechatAdapter, wechatBaseUrl, wechatApiKey, wechatMerchantId));
        settings.put(
                PaymentProviderIds.ALIPAY,
                new PaymentProviderSettings(alipayAdapter, alipayBaseUrl, alipayApiKey, alipayMerchantId));
        return new JdbcPaymentPort(
                jdbc,
                transactionTemplate,
                clock,
                objectMapper,
                PaymentProviderAdapterFactory.createAll(
                        environment.getActiveProfiles(),
                        settings,
                        paymentHttpClient,
                        paymentAdapterSink),
                auditPort);
    }

    /**
     * Operator read-only payment and refund query for the console list. No create, callback, or refund.
     * 操作员只读支付与退款查询，供控制台列表使用；不能创单、办理回调或退款。
     */
    @Bean
    OperatorPaymentService operatorPaymentService(
            JdbcPaymentPort paymentPort,
            OperatorTenantAccess operatorTenantAccess,
            PolicyEngine policyEngine) {
        return new OperatorPaymentService(paymentPort, operatorTenantAccess, policyEngine);
    }

    @Bean
    RecordingNotificationSink notificationDispatchSink() {
        return new RecordingNotificationSink();
    }

    /**
     * JDK transport for the vendor-shaped notification template. Selection still requires explicit config.
     * 厂商形通知模板的 JDK 传输。是否启用仍须显式配置，仅存在本 Bean 不会外呼。
     */
    @Bean
    NotificationHttpClient notificationHttpClient() {
        return new JdkNotificationHttpClient();
    }

    /**
     * Channel adapters from explicit per-channel config. Production stays fail-closed unless
     * {@code adapter=http} is complete. {@code adapter=mock} is refused in production.
     * Incomplete vendor config never falls back to the mock adapter. Bodies and keys are never logged.
     * 按各渠道显式配置选择适配器。生产默认失败关闭；仅在配齐 {@code adapter=http} 时启用模板。
     * 生产禁止 {@code adapter=mock}。厂商配置不完整绝不回退到模拟适配器。请求体与密钥不写入日志。
     */
    @Bean
    NotificationDispatcher notificationDispatcher(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            DistributedLockPort lock,
            ObjectMapper objectMapper,
            Clock clock,
            Environment environment,
            NotificationHttpClient notificationHttpClient,
            RecordingNotificationSink notificationDispatchSink,
            AuditPort auditPort,
            @Value("${platform.notification.sms.adapter:}") String smsAdapter,
            @Value("${platform.notification.sms.http.base-url:}") String smsBaseUrl,
            @Value("${platform.notification.sms.http.api-key:}") String smsApiKey,
            @Value("${platform.notification.sms.http.from:}") String smsFrom,
            @Value("${platform.notification.email.adapter:}") String emailAdapter,
            @Value("${platform.notification.email.http.base-url:}") String emailBaseUrl,
            @Value("${platform.notification.email.http.api-key:}") String emailApiKey,
            @Value("${platform.notification.email.http.from:}") String emailFrom,
            @Value("${platform.notification.wechat-template.adapter:}") String wechatAdapter,
            @Value("${platform.notification.wechat-template.http.base-url:}") String wechatBaseUrl,
            @Value("${platform.notification.wechat-template.http.api-key:}") String wechatApiKey,
            @Value("${platform.notification.wechat-template.http.from:}") String wechatFrom) {
        java.util.EnumMap<NotificationChannel, NotificationChannelSettings> settings =
                new java.util.EnumMap<>(NotificationChannel.class);
        settings.put(
                NotificationChannel.SMS,
                new NotificationChannelSettings(smsAdapter, smsBaseUrl, smsApiKey, smsFrom));
        settings.put(
                NotificationChannel.EMAIL,
                new NotificationChannelSettings(emailAdapter, emailBaseUrl, emailApiKey, emailFrom));
        settings.put(
                NotificationChannel.WECHAT_TEMPLATE,
                new NotificationChannelSettings(wechatAdapter, wechatBaseUrl, wechatApiKey, wechatFrom));
        return new JdbcNotificationDispatcher(
                jdbc,
                transactionTemplate,
                lock,
                objectMapper,
                clock,
                NotificationChannelAdapterFactory.createAll(
                        environment.getActiveProfiles(),
                        settings,
                        notificationHttpClient,
                        notificationDispatchSink),
                auditPort);
    }


    @Bean
    InboxPort inboxPort(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        return new JdbcInboxPort(jdbc, transactionTemplate);
    }

    @Bean
    QuestionnairePort questionnairePort(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            Clock clock,
            RateLimitPort rateLimitPort) {
        return new JdbcQuestionnairePort(jdbc, transactionTemplate, clock, rateLimitPort);
    }

    @Bean
    QuestionnaireService questionnaireService(
            QuestionnairePort questionnairePort,
            OperatorTenantAccess operatorTenantAccess,
            TenantGuard tenantGuard) {
        return new QuestionnaireService(questionnairePort, operatorTenantAccess, tenantGuard);
    }

    /**
     * Tenant-scoped questionnaire assignments (AUTH_END_USER visibility).
     * 租户内问卷分配（认证终端可见性）。
     */
    @Bean
    QuestionnaireAssignmentStore questionnaireAssignmentStore(JdbcTemplate jdbc) {
        return new JdbcQuestionnaireAssignmentStore(jdbc);
    }

    @Bean
    QuestionnaireAssignmentVisibility questionnaireAssignmentVisibility(
            QuestionnaireAssignmentStore questionnaireAssignmentStore,
            JdbcOrganizationStore jdbcOrganizationStore) {
        return new QuestionnaireAssignmentVisibility(questionnaireAssignmentStore, jdbcOrganizationStore);
    }

    @Bean
    QuestionnaireAssignmentService questionnaireAssignmentService(
            QuestionnairePort questionnairePort,
            QuestionnaireAssignmentStore questionnaireAssignmentStore,
            JdbcOrganizationStore jdbcOrganizationStore,
            JdbcTemplate jdbc,
            OperatorTenantAccess operatorTenantAccess,
            TenantGuard tenantGuard,
            AuditPort auditPort,
            Clock clock) {
        return new QuestionnaireAssignmentService(
                questionnairePort,
                questionnaireAssignmentStore,
                jdbcOrganizationStore,
                jdbc,
                operatorTenantAccess,
                tenantGuard,
                auditPort,
                clock);
    }

    @Bean
    InboxGroupPort inboxGroupPort(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        return new JdbcInboxGroupPort(jdbc, transactionTemplate);
    }

    @Bean
    InboxGroupService inboxGroupService(
            InboxGroupPort inboxGroupPort,
            OperatorTenantAccess operatorTenantAccess,
            TenantGuard tenantGuard,
            Clock clock) {
        return new InboxGroupService(inboxGroupPort, operatorTenantAccess, tenantGuard, clock);
    }


    @Bean
    InboxAttachmentStagingStore inboxAttachmentStagingStore(Clock clock, ObjectStorage objectStorage) {
        return new InboxAttachmentStagingStore(clock, objectStorage);
    }

    @Bean
    InboxOrgBroadcastExpander inboxOrgBroadcastExpander(
            JdbcOrganizationStore jdbcOrganizationStore, OrganizationScopeResolver organizationScopeResolver) {
        return new InboxOrgBroadcastExpander(jdbcOrganizationStore, organizationScopeResolver);
    }

    @Bean
    InboxFanoutWorker inboxFanoutWorker(InboxPort inboxPort) {
        // Same-thread drain keeps local/dev deterministic; production can swap Executor.
        // 同线程排空便于本地确定性；生产可换线程池。
        return new InboxFanoutWorker(inboxPort, Runnable::run);
    }

    @Bean
    InboxService inboxService(
            InboxPort inboxPort,
            InboxGroupPort inboxGroupPort,
            InboxOrgBroadcastExpander inboxOrgBroadcastExpander,
            OperatorTenantAccess operatorTenantAccess,
            TenantGuard tenantGuard,
            Clock clock,
            ObjectStorage objectStorage,
            ChunkedBlobUploadStore chunkedBlobUploadStore,
            InboxAttachmentStagingStore inboxAttachmentStagingStore,
            InboxFanoutWorker inboxFanoutWorker,
            ObjectUploadLimits objectUploadLimits) {
        return new InboxService(
                inboxPort,
                inboxGroupPort,
                inboxOrgBroadcastExpander,
                operatorTenantAccess,
                tenantGuard,
                clock,
                objectStorage,
                chunkedBlobUploadStore,
                inboxAttachmentStagingStore,
                inboxFanoutWorker,
                objectUploadLimits);
    }

    @Bean
    JdbcWorkflowStore jdbcWorkflowStore(JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        return new JdbcWorkflowStore(jdbc, transactionTemplate);
    }

    @Bean
    WorkflowOrgApproverGate workflowOrgApproverGate(
            HybridEntityStore hybridEntityStore,
            EntityCatalog entityCatalog,
            OrganizationScopeResolver organizationScopeResolver,
            JdbcOrganizationStore jdbcOrganizationStore,
            WorkflowCatalog workflowCatalog) {
        return new WorkflowOrgApproverGate(
                hybridEntityStore,
                entityCatalog,
                organizationScopeResolver,
                jdbcOrganizationStore,
                workflowCatalog);
    }

    @Bean
    RepairTicketWorkflowInboxHook repairTicketWorkflowInboxHook(
            InboxPort inboxPort, WorkflowOrgApproverGate workflowOrgApproverGate) {
        return new RepairTicketWorkflowInboxHook(inboxPort, workflowOrgApproverGate);
    }

    @Bean
    WorkflowService workflowService(
            JdbcWorkflowStore jdbcWorkflowStore,
            OperatorTenantAccess operatorTenantAccess,
            CapabilityCatalog capabilityCatalog,
            OperatorActionAudit operatorActionAudit,
            Clock clock,
            WorkflowOrgApproverGate workflowOrgApproverGate,
            EffectiveDeclarationService effectiveDeclarationService,
            RepairTicketWorkflowInboxHook repairTicketWorkflowInboxHook,
            WorkflowCatalog workflowCatalog) {
        return new WorkflowService(
                jdbcWorkflowStore,
                operatorTenantAccess,
                capabilityCatalog,
                operatorActionAudit,
                clock,
                workflowOrgApproverGate,
                effectiveDeclarationService,
                repairTicketWorkflowInboxHook,
                workflowCatalog);
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
            Clock clock,
            @Value("${platform.tenant.auto-create-on-submit:false}") boolean autoCreateTenantOnSubmit) {
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
                jdbc,
                transaction,
                idempotencyPort,
                auditPort,
                lock,
                port,
                openTelemetry,
                objectMapper,
                clock,
                autoCreateTenantOnSubmit);
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
     * Hybrid B shared entity_record store (ADR 0002 / ES-1) — 混合 B 共享 entity_record 存储。
     */
    @Bean
    HybridEntityStore hybridEntityStore(
            JdbcTemplate jdbc, Clock clock, ObjectMapper objectMapper, ConfiguredConnection connection) {
        // SHARED-1: PostgreSQL uses attrs @> + GIN; MySQL keeps in-memory scan.
        // SHARED-1：PostgreSQL 走 attrs @> + GIN；MySQL 仍内存扫描。
        boolean jsonbFilter = AttrsJsonbQueries.sqlContainmentEnabled(connection.vendor());
        return new JdbcHybridEntityStore(jdbc, clock, objectMapper, jsonbFilter);
    }


    /**
     * Search read path MVP — read-only entity_record substring / optional attrs equality.
     * 搜索读路径 MVP：只读 entity_record 子串 / 可选 attrs 等值。
     */
    @Bean
    EntityRecordSearchStore entityRecordSearchStore(
            JdbcTemplate jdbc, ObjectMapper objectMapper, ConfiguredConnection connection) {
        boolean jsonb = AttrsJsonbQueries.sqlContainmentEnabled(connection.vendor());
        return new JdbcEntityRecordSearchStore(jdbc, objectMapper, jsonb);
    }

    /**
     * Entity attachment metadata + ObjectStorage bytes (ES-2 / ADR 0002) —
     * 实体附件元数据 + ObjectStorage 字节（ES-2）。
     */
    /**
     * WP3-3 generic single-file cap (ADR 0015 D4) — 通用单文件上限。
     * Key: {@code platform.storage.upload.max-bytes} / {@code PLATFORM_STORAGE_UPLOAD_MAX_BYTES} (default 50MB).
     */
    @Bean
    ObjectUploadLimits objectUploadLimits(
            @Value("${platform.storage.upload.max-bytes:50MB}") String maxBytes) {
        return new ObjectUploadLimits(
                ObjectUploadLimits.parseMaxBytes(maxBytes, ObjectUploadLimits.DEFAULT_MAX_BYTES));
    }

    @Bean
    EntityBlobStore entityBlobStore(
            JdbcTemplate jdbc, ObjectStorage objectStorage, Clock clock, ObjectUploadLimits objectUploadLimits) {
        return new JdbcEntityBlobStore(jdbc, objectStorage, clock, objectUploadLimits);
    }

    /**
     * Authorized-object registry and grants (ADR 0015 / WP3-1), above the blob port.
     * 授权对象登记与授权（在对象存储端口之上）。
     */
    @Bean
    ObjectAccessAudit objectAccessAudit(AuditPort auditPort, Clock clock) {
        return new ObjectAccessAudit(auditPort, clock);
    }

    @Bean
    ObjectGrantStore objectGrantStore(JdbcTemplate jdbc, Clock clock, ObjectAccessAudit objectAccessAudit) {
        return new JdbcObjectGrantStore(jdbc, clock, objectAccessAudit);
    }

    /**
     * End-user object upload and list (ADR 0019), including CHUNK-1 sessions on ObjectGrantStore.
     * 终端对象上传与列表；分片复用 CHUNK-1 会话，完成后登记到授权对象轨。
     */
    @Bean
    EndUserObjectService endUserObjectService(
            EndUserAccountStore endUserAccountStore,
            ObjectStorage objectStorage,
            ObjectGrantStore objectGrantStore,
            ObjectUploadLimits objectUploadLimits,
            ObjectAccessAudit objectAccessAudit,
            ChunkedBlobUploadStore chunkedBlobUploadStore) {
        return new EndUserObjectService(
                endUserAccountStore,
                objectStorage,
                objectGrantStore,
                objectUploadLimits,
                objectAccessAudit,
                chunkedBlobUploadStore);
    }

    @Bean
    AuthorizedObjectRegistrar authorizedObjectRegistrar(
            EntityBlobStore entityBlobStore, ObjectGrantStore objectGrantStore, ObjectAccessAudit objectAccessAudit) {
        return new AuthorizedObjectRegistrar(entityBlobStore, objectGrantStore, objectAccessAudit);
    }

    /**
     * Deny-by-default owner-or-grant decision (WP3-1). Does not issue signed URLs.
     * 默认拒绝的所有者或显式授权判定。不签发签名链接。
     */
    @Bean
    ObjectAccessAuthorizer objectAccessAuthorizer(ObjectGrantStore objectGrantStore, JdbcTemplate jdbc, Clock clock) {
        return new ObjectAccessAuthorizer(objectGrantStore, jdbc, clock);
    }

    /**
     * WP3-2 signed-download settings (ADR 0015 D3) — 短时签名下载配置。
     * Keys: {@code platform.storage.download.ttl} / {@code PLATFORM_STORAGE_DOWNLOAD_TTL} (default PT15M),
     * {@code platform.storage.download.ttl-max} / {@code PLATFORM_STORAGE_DOWNLOAD_TTL_MAX} (hard cap PT1H in code),
     * {@code platform.storage.download.public-base-url} / {@code PLATFORM_STORAGE_DOWNLOAD_PUBLIC_BASE_URL},
     * {@code platform.storage.download.signing-key} / {@code PLATFORM_STORAGE_DOWNLOAD_SIGNING_KEY}.
     */
    @Bean
    ObjectDownloadProperties objectDownloadProperties(
            @Value("${platform.storage.download.ttl:PT15M}") String ttl,
            @Value("${platform.storage.download.ttl-max:PT1H}") String ttlMax,
            @Value("${platform.storage.download.public-base-url:http://127.0.0.1:8080}") String publicBaseUrl,
            @Value("${platform.storage.download.signing-key:}") String signingKey) {
        return new ObjectDownloadProperties(
                ObjectDownloadProperties.parseIso(ttl, ObjectDownloadProperties.DEFAULT_TTL),
                ObjectDownloadProperties.parseIso(ttlMax, ObjectDownloadProperties.HARD_CAP),
                publicBaseUrl,
                signingKey);
    }

    @Bean
    LocalDownloadSignature localDownloadSignature(ObjectDownloadProperties properties) {
        return new LocalDownloadSignature(properties.signingKey());
    }

    @Bean
    ObjectDownloadUrlIssuer objectDownloadUrlIssuer(
            ObjectStorage objectStorage, ObjectDownloadProperties properties, LocalDownloadSignature signature, Clock clock) {
        if (objectStorage instanceof S3ObjectStorage s3) {
            return new S3PresignedDownloadIssuer(s3, clock);
        }
        return new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock);
    }

    @Bean
    ObjectDownloadLinkService objectDownloadLinkService(
            ObjectAccessAuthorizer authorizer,
            ObjectDownloadUrlIssuer issuer,
            LocalDownloadSignature signature,
            ObjectStorage objectStorage,
            ObjectDownloadProperties properties,
            Clock clock,
            ObjectAccessAudit objectAccessAudit) {
        return new ObjectDownloadLinkService(
                authorizer, issuer, signature, objectStorage, properties, clock, objectAccessAudit);
    }


    /**
     * CHUNK-1: local temp chunk sessions; assemble then EntityBlobStore.put (works for local|s3 ObjectStorage) —
     * CHUNK-1：本地临时分片会话；组装后走 EntityBlobStore.put（local/s3 对象存储均可）。
     */
    @Bean
    ChunkedBlobUploadStore chunkedBlobUploadStore(
            @Value("${platform.storage.chunk-upload-directory:object-store-chunks}") String directory,
            Clock clock) {
        return new LocalDirChunkedBlobUploadStore(Path.of(directory), clock);
    }


    /**
     * ID-1 thin OIDC OP — 轻量 OIDC 签发方配置与 JWT/JWKS。
     */
    @Bean
    OpProperties opProperties(
            @Value("${platform.op.issuer:http://127.0.0.1:8080}") String issuer,
            @Value("${platform.op.access-token-ttl:PT30M}") Duration accessTtl,
            @Value("${platform.op.id-token-ttl:PT30M}") Duration idTtl,
            @Value("${platform.op.auth-code-ttl:PT5M}") Duration codeTtl,
            @Value("${platform.op.console-client-id:subjex-console}") String consoleClientId) {
        return new OpProperties(issuer, accessTtl, idTtl, codeTtl, consoleClientId);
    }

    @Bean
    OpSigningKeyService opSigningKeyService(JdbcTemplate jdbc, Clock clock) {
        return new OpSigningKeyService(jdbc, clock);
    }

    @Bean
    OpJwtService opJwtService(OpProperties opProperties, OpSigningKeyService opSigningKeyService) {
        return new OpJwtService(opProperties, opSigningKeyService);
    }

    @Bean
    JdbcOauthClientStore jdbcOauthClientStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcOauthClientStore(jdbc, clock);
    }

    @Bean
    JdbcOauthAuthCodeStore jdbcOauthAuthCodeStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcOauthAuthCodeStore(jdbc, clock);
    }

    @Bean
    JdbcOauthScopeCatalog jdbcOauthScopeCatalog(JdbcTemplate jdbc) {
        return new JdbcOauthScopeCatalog(jdbc);
    }

    @Bean
    JdbcOauthConsentStore jdbcOauthConsentStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcOauthConsentStore(jdbc, clock);
    }

    @Bean
    JdbcOauthConsentChallengeStore jdbcOauthConsentChallengeStore(JdbcTemplate jdbc, Clock clock) {
        return new JdbcOauthConsentChallengeStore(jdbc, clock);
    }

    /**
     * Thin algorithm/AI capability catalogs (classpath YAML) — 薄算法/AI 能力目录（classpath YAML）。
     */
    @Bean
    CapabilityCatalog capabilityCatalog() {
        return new CapabilityCatalog();
    }

    /**
     * UI permission Manifest classpath baseline (ADR 0010 / PC-1) — UI 权限 Manifest classpath 基线。
     * <p>
     * Fail-closed on unknown permission names at startup.
     * 启动时未知权限名失败关闭。
     */
    @Bean
    UiPermissionManifestCatalog uiPermissionManifestCatalog() {
        return new UiPermissionManifestCatalog();
    }

    /**
     * UI permission Manifest drafts + promote (declaration-store dual-track, PC-6a/6b) —
     * UI 权限 Manifest 草稿与晋升（声明库双轨语义，PC-6a/6b）。
     * <p>
     * Live GET resolves PROMOTED overlay over classpath baseline.
     * 线上 GET：已晋升叠层优先于 classpath 基线。
     */
    @Bean
    UiPermissionDraftService uiPermissionDraftService(
            JdbcDeclarationStore declarationStore,
            UiPermissionManifestCatalog uiPermissionManifestCatalog,
            InternalDeclarationGit internalDeclarationGit,
            TransactionTemplate transactionTemplate,
            @Value("${platform.declaration.git.dir:./data/declaration-git}") String gitDir) {
        return new UiPermissionDraftService(
                declarationStore,
                uiPermissionManifestCatalog,
                internalDeclarationGit,
                Path.of(gitDir),
                transactionTemplate);
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
     * Dispatch form domainAction via registered {@link com.subjex.platform.app.form.DomainActionHandler} beans.
     * 经已注册的 DomainActionHandler Bean 分发表单领域动作。
     */
    @Bean
    FormDomainActionRunner formDomainActionRunner(List<DomainActionHandler> domainActionHandlers) {
        return new FormDomainActionRunner(domainActionHandlers);
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
