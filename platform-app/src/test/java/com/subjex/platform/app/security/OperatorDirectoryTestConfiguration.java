package com.subjex.platform.app.security;

import com.subjex.platform.app.identity.JdbcOauthAuthCodeStore;
import com.subjex.platform.app.identity.JdbcOauthClientStore;
import com.subjex.platform.app.identity.JdbcOauthConsentChallengeStore;
import com.subjex.platform.app.identity.JdbcOauthConsentStore;
import com.subjex.platform.app.identity.JdbcOauthScopeCatalog;
import com.subjex.platform.app.identity.OpJwtService;
import com.subjex.platform.app.idp.WeChatProperties;
import com.subjex.platform.app.idp.WeChatIdpAdapter;
import com.subjex.platform.app.idp.WeChatApiClient;
import com.subjex.platform.app.idp.IdpLoginOrchestrator;
import com.subjex.platform.app.idp.IdpAdapterRegistry;
import com.subjex.platform.app.idp.IdpAdapter;
import com.subjex.platform.app.idp.GenericOidcIdpAdapter;
import com.subjex.platform.app.identity.OpProperties;
import com.subjex.platform.app.identity.OpSigningKeyService;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.contract.audit.AuditPort;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OperatorDirectoryTestConfiguration — 测试用操作员名录：真实迁移、真实本地种子、真实 JDBC 名录。
 * <p>
 * Web slice tests sign in through the same {@link JdbcOperatorDirectory} the application uses.
 * The local operator {@code platform-operator} / {@code change-me} holds every permission.
 * {@code platform-viewer} / {@code viewer-pass} holds the role {@code platform-reader} only.
 * Web 切片测试通过应用自己的 {@link JdbcOperatorDirectory} 登录。
 * 本地操作员 {@code platform-operator} / {@code change-me} 拥有全部权限。
 * {@code platform-viewer} / {@code viewer-pass} 只有角色 {@code platform-reader}。
 */
@TestConfiguration
public class OperatorDirectoryTestConfiguration {

    public static final String OPERATOR = "platform-operator";
    public static final String OPERATOR_PASSWORD = "change-me";
    public static final String VIEWER = "platform-viewer";
    public static final String VIEWER_PASSWORD = "viewer-pass";

    @Bean
    DataSource operatorTables() {
        return H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource operatorTables) {
        return new JdbcTemplate(operatorTables);
    }

    @Bean
    JdbcOperatorDirectory operatorDirectory(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, DataSource source) {
        TransactionTemplate transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        new LocalOperatorSeeder(jdbc, transaction, passwordEncoder).seed(OPERATOR, OPERATOR_PASSWORD);
        addViewer(jdbc, passwordEncoder);
        return new JdbcOperatorDirectory(jdbc);
    }

    @Bean
    AuditPort auditPort(JdbcTemplate jdbc) {
        return new JdbcAuditPort(jdbc);
    }

    @Bean
    OperatorActionAudit operatorActionAudit(AuditPort auditPort) {
        return new OperatorActionAudit(auditPort, Clock.systemUTC());
    }

    @Bean
    OperatorTenantAccess operatorTenantAccess(JdbcTemplate jdbc, DataSource operatorTables) {
        return new JdbcOperatorTenantAccess(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(operatorTables)));
    }

    @Bean
    JdbcOperatorAdmin jdbcOperatorAdmin(
            JdbcTemplate jdbc, DataSource operatorTables, PasswordEncoder passwordEncoder, OperatorTenantAccess tenantAccess) {
        return new JdbcOperatorAdmin(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(operatorTables)),
                passwordEncoder,
                tenantAccess);
    }

    @Bean
    JdbcTenantAdmin jdbcTenantAdmin(JdbcTemplate jdbc) {
        return new JdbcTenantAdmin(jdbc);
    }

    @Bean
    JdbcOperatorTokenStore operatorTokenStore(
            JdbcTemplate jdbc, DataSource operatorTables, JdbcOperatorDirectory operatorDirectory) {
        return new JdbcOperatorTokenStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(operatorTables)),
                operatorDirectory,
                Clock.systemUTC(),
                Duration.ofMinutes(30),
                Duration.ofHours(8));
    }

    @Bean
    JdbcOperatorLoginLockout operatorLoginLockout(
            JdbcTemplate jdbc,
            DataSource operatorTables,
            @Value("${platform.auth.lockout-max-failures:5}") int maxFailures,
            @Value("${platform.auth.lockout-duration:PT15M}") Duration lockoutDuration) {
        return new JdbcOperatorLoginLockout(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(operatorTables)),
                Clock.systemUTC(),
                maxFailures,
                lockoutDuration);
    }

    @Bean
    AesGcmSecretCipher mfaSecretCipher() {
        return new AesGcmSecretCipher("test-mfa-encryption-key-32chars!!");
    }

    @Bean
    JdbcOperatorMfaStore operatorMfaStore(
            JdbcTemplate jdbc,
            DataSource operatorTables,
            JdbcOperatorDirectory operatorDirectory,
            AesGcmSecretCipher mfaSecretCipher) {
        return new JdbcOperatorMfaStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(operatorTables)),
                operatorDirectory,
                mfaSecretCipher,
                Clock.systemUTC(),
                Duration.ofMinutes(5),
                "subjex-test",
                false,
                false);
    }


    @Bean
    JdbcOperatorIdpLinkStore operatorIdpLinkStore(JdbcTemplate jdbc, DataSource operatorTables) {
        return new JdbcOperatorIdpLinkStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(operatorTables)), Clock.systemUTC());
    }

    @Bean
    OidcProperties oidcProperties() {
        return new OidcProperties(
                true,
                "https://idp.example/realms/subjex",
                "subjex-console",
                "test-secret",
                "http://localhost:3000/api/session/oidc/callback",
                java.util.List.of("openid", "profile", "email"),
                Duration.ofMinutes(10));
    }

    @Bean
    JdbcOidcLoginStore oidcLoginStore(JdbcTemplate jdbc, DataSource operatorTables, OidcProperties oidcProperties) {
        return new JdbcOidcLoginStore(
                jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(operatorTables)),
                Clock.systemUTC(),
                oidcProperties.loginTtl());
    }

    @Bean
    OidcTokenClient oidcTokenClient() {
        // Default stub: tests that need claims replace this bean in their own @TestConfiguration.
        // 默认桩：需要声明的测试在自己的 TestConfiguration 里覆盖。
        return (pending, code) -> {
            throw new InvalidOperatorTokenException("oidc-stub-not-configured");
        };
    }

    @Bean
    WeChatProperties weChatProperties() {
        return new WeChatProperties(false, "", "", "");
    }

    @Bean
    WeChatApiClient weChatApiClient() {
        return new WeChatApiClient() {
            @Override
            public WeChatApiClient.WeChatSession redeemOpenCode(String code) {
                throw new InvalidOperatorTokenException("wechat-stub-not-configured");
            }

            @Override
            public WeChatApiClient.WeChatSession code2Session(String jsCode) {
                throw new InvalidOperatorTokenException("wechat-stub-not-configured");
            }
        };
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

    /** A second operator with read-only permissions — 第二位只读操作员。 */
    static void addViewer(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        jdbc.update("INSERT INTO account (account_id, login_name, account_state) VALUES ('account-viewer', ?, 'ACTIVE')",
                VIEWER);
        jdbc.update("INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES ('subject-viewer', 'Viewer', 'PERSON')");
        jdbc.update("""
                INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                VALUES ('identity-viewer', 'account-viewer', 'subject-viewer', 'platform', 'ACTIVE')
                """);
        jdbc.update("INSERT INTO operator_credential (account_id, password_hash) VALUES ('account-viewer', ?)",
                passwordEncoder.encode(VIEWER_PASSWORD));
        jdbc.update("INSERT INTO subject_role (subject_id, role_name) VALUES ('subject-viewer', 'platform-reader')");
    }

    /** ID-1 OP beans for WebMvc dual-accept Bearer — WebMvc 双受理 Bearer 用 OP Bean。 */
    @Bean
    OpProperties opProperties() {
        return new OpProperties(
                "http://127.0.0.1:8080",
                Duration.ofMinutes(30),
                Duration.ofMinutes(30),
                Duration.ofMinutes(5),
                "subjex-console");
    }

    @Bean
    OpSigningKeyService opSigningKeyService(JdbcTemplate jdbc) {
        return new OpSigningKeyService(jdbc, Clock.systemUTC());
    }

    @Bean
    OpJwtService opJwtService(OpProperties opProperties, OpSigningKeyService opSigningKeyService) {
        return new OpJwtService(opProperties, opSigningKeyService);
    }

    @Bean
    JdbcOauthClientStore jdbcOauthClientStore(JdbcTemplate jdbc) {
        return new JdbcOauthClientStore(jdbc, Clock.systemUTC());
    }

    @Bean
    JdbcOauthAuthCodeStore jdbcOauthAuthCodeStore(JdbcTemplate jdbc) {
        return new JdbcOauthAuthCodeStore(jdbc, Clock.systemUTC());
    }


    @Bean
    JdbcOauthScopeCatalog jdbcOauthScopeCatalog(JdbcTemplate jdbc) {
        return new JdbcOauthScopeCatalog(jdbc);
    }

    @Bean
    JdbcOauthConsentStore jdbcOauthConsentStore(JdbcTemplate jdbc) {
        return new JdbcOauthConsentStore(jdbc, Clock.systemUTC());
    }

    @Bean
    JdbcOauthConsentChallengeStore jdbcOauthConsentChallengeStore(JdbcTemplate jdbc) {
        return new JdbcOauthConsentChallengeStore(jdbc, Clock.systemUTC());
    }

}
