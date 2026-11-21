package com.subjex.platform.app.storage;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.admin.AuditApiEndpoint;
import com.subjex.platform.app.api.JsonApi;
import com.subjex.platform.app.entity.AuthorizedObjectRegistrar;
import com.subjex.platform.app.entity.EntityBlobStore;
import com.subjex.platform.app.entity.JdbcEntityBlobStore;
import com.subjex.platform.app.identity.enduser.EndUserAccountStore;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.identity.enduser.JdbcEndUserTokenStore;
import com.subjex.platform.app.jdbc.JdbcAdminReader;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.security.LocalOperatorSeeder;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.audit.AuditPort;
import com.subjex.platform.contract.storage.ObjectStorage;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * ObjectAccessIsolationTest — formal WP3-5 HTTP isolation and audit-trace suite (ADR 0015).
 * Locks: unauthorized subjects get no usable signed URL; authorized issue is queryable
 * via {@code GET /api/v1/audit} scoped by tenant; prod still defaults to shared S3 storage.
 * <p>
 * 正式套件：未授权主体拿不到可用签名 URL；已授权签发可按租户在既有审计查询中追溯；
 * 生产仍默认共享 S3，不回退本地盘。
 */
@WebMvcTest(controllers = {ObjectDownloadEndpoint.class, AuditApiEndpoint.class})
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    ObjectAccessIsolationTest.GuardConfiguration.class,
    ObjectAccessIsolationTest.IsolationConfiguration.class
})
@TestPropertySource(properties = "platform.auth.http-basic-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ObjectAccessIsolationTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";
    private static final String ORG = "org-wp35";
    private static final byte[] PAYLOAD = "wp3-5-bytes".getBytes(StandardCharsets.UTF_8);
    static final String SIGNING_KEY = "wp3-5-isolation-hmac-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JdbcEndUserAccountStore accounts;

    @Autowired
    private JdbcEndUserTokenStore endUserTokens;

    @Autowired
    private JdbcObjectGrantStore grants;

    @Autowired
    private AuthorizedObjectRegistrar registrar;

    @Autowired
    private LocalDownloadSignature signature;

    @Autowired
    private JdbcOrganizationStore orgs;

    private String userAAccess;
    private String userASubject;
    private String userBAccess;
    private String memberAccess;
    private String memberSubject;

    @BeforeEach
    void setUpUsersAndTenants() {
        ensureTenant(TENANT_A, "Tenant A");
        ensureTenant(TENANT_B, "Tenant B");
        EndUserRecord userA = accounts.provision(TENANT_A, "User A");
        userASubject = userA.subject().subjectId();
        userAAccess = endUserTokens.issue(userA.account().accountId(), TENANT_A, "JUnit", "127.0.0.1").accessToken();
        EndUserRecord userB = accounts.provision(TENANT_B, "User B");
        userBAccess = endUserTokens.issue(userB.account().accountId(), TENANT_B, "JUnit", "127.0.0.1").accessToken();
        EndUserRecord member = accounts.provision(TENANT_A, "Org Member");
        memberSubject = member.subject().subjectId();
        memberAccess = endUserTokens
                .issue(member.account().accountId(), TENANT_A, "JUnit", "127.0.0.1")
                .accessToken();
        orgs.upsertOrganization(ORG, "WP3-5 Org", JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertTenantOrganization(TENANT_A, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        orgs.upsertMembership(memberSubject, ORG, JdbcOrganizationStore.STATE_ACTIVE);
    }

    @Test
    void productionDefaultsToSharedS3AndRefusesLocalDiskFallback() throws Exception {
        assertEquals("s3", propertyWithProfiles("prod"));
        assertEquals("s3", propertyWithProfiles("production"));
        IllegalStateException local = assertThrows(
                IllegalStateException.class,
                () -> ObjectStorageFactory.create(
                        "local", "object-store", "", "us-east-1", "", "", "", true, true));
        assertTrue(local.getMessage().contains("not allowed under prod"));
        IllegalStateException blank = assertThrows(
                IllegalStateException.class,
                () -> ObjectStorageFactory.create("", "object-store", "", "us-east-1", "", "", "", true, true));
        assertTrue(blank.getMessage().contains("must be s3"));
    }

    @Test
    void ownerCanIssueAndStrangerWithoutGrantCannotThenGrantAndRevoke() throws Exception {
        String key = registerOwned();
        String url = issueOperatorOk(TENANT_A, key);
        assertTrue(url.contains(ObjectDownloadEndpoint.REDEEM_PATH));

        mockMvc.perform(issueEndUser(userAAccess, TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());

        grants.grantToSubject(TENANT_A, key, userASubject, null, LocalOperatorSeeder.SUBJECT_ID);
        String granteeUrl = issueEndUserOk(userAAccess, TENANT_A, key);
        assertTrue(granteeUrl.contains("sig="));

        grants.revoke(grants.listGrants(TENANT_A, key).get(0).grantId(), LocalOperatorSeeder.SUBJECT_ID);
        mockMvc.perform(issueEndUser(userAAccess, TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void organizationGrantAllowsOnlyActiveMembershipAndDeniesAfterEndOrExpiry() throws Exception {
        String key = registerOwned();

        mockMvc.perform(issueEndUser(memberAccess, TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON));

        grants.grantToOrganization(TENANT_A, key, ORG, null, LocalOperatorSeeder.SUBJECT_ID);
        issueEndUserOk(memberAccess, TENANT_A, key);

        orgs.upsertMembership(memberSubject, ORG, JdbcOrganizationStore.STATE_ENDED);
        mockMvc.perform(issueEndUser(memberAccess, TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());

        orgs.upsertMembership(memberSubject, ORG, JdbcOrganizationStore.STATE_ACTIVE);
        issueEndUserOk(memberAccess, TENANT_A, key);

        String expiredKey = registerOwned();
        grants.grantToOrganization(
                TENANT_A, expiredKey, ORG, Instant.now().minusSeconds(30), LocalOperatorSeeder.SUBJECT_ID);
        mockMvc.perform(issueEndUser(memberAccess, TENANT_A, expiredKey))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON));
    }

    @Test
    void crossTenantCannotIssueAndAuditQueryStaysTenantScoped() throws Exception {
        String key = registerOwned();
        issueOperatorOk(TENANT_A, key);

        mockMvc.perform(issueEndUser(userBAccess, TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());
        mockMvc.perform(issueOperator(TENANT_B, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());

        MvcResult allowed = mockMvc.perform(auditQuery(TENANT_A, ObjectAccessAudit.DOWNLOAD_ISSUE))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(auditMentions(allowed, key), "tenant-A audit should list the authorized issue");

        MvcResult other = mockMvc.perform(auditQuery(TENANT_B, ObjectAccessAudit.DOWNLOAD_ISSUE))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(
                auditHasTenant(other, TENANT_A),
                "tenant-B audit must not include tenant-A records");
    }

    @Test
    void endUserAndOperatorRealmsStaySplitAndAuditStaysOperatorOnly() throws Exception {
        String key = registerOwned();

        mockMvc.perform(post(ObjectDownloadEndpoint.OPERATOR_ISSUE_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody(TENANT_A, key))
                        .header("Authorization", "Bearer " + userAAccess))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("operator-authentication-required"));

        mockMvc.perform(issueEndUserWithBasic(TENANT_A, key)).andExpect(status().isForbidden());

        mockMvc.perform(get(JsonApi.BASE + "/audit")
                        .param("tenantId", TENANT_A)
                        .header("Authorization", "Bearer " + userAAccess))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("operator-authentication-required"));
    }

    @Test
    void authorizedIssueIsAuditQueryableAndDeniedIssueHasNoUrl() throws Exception {
        String key = registerOwned();
        grants.grantToSubject(TENANT_A, key, userASubject, null, LocalOperatorSeeder.SUBJECT_ID);
        issueEndUserOk(userAAccess, TENANT_A, key);

        MvcResult upload = mockMvc.perform(auditQuery(TENANT_A, ObjectAccessAudit.UPLOAD))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(auditMentions(upload, key));

        MvcResult issue = mockMvc.perform(auditQuery(TENANT_A, ObjectAccessAudit.DOWNLOAD_ISSUE))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(auditMentions(issue, key));

        MvcResult grant = mockMvc.perform(auditQuery(TENANT_A, ObjectAccessAudit.GRANT_CREATE))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(auditMentions(grant, key));

        mockMvc.perform(issueEndUser(userBAccess, TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void localRedeemRequiresValidHmacAndRejectsTamperedOrExpired() throws Exception {
        String key = registerOwned();
        String url = issueOperatorOk(TENANT_A, key);
        String pathAndQuery = url.substring(url.indexOf(ObjectDownloadEndpoint.REDEEM_PATH));
        mockMvc.perform(get(pathAndQuery))
                .andExpect(status().isOk())
                .andExpect(result -> assertEquals(
                        new String(PAYLOAD, StandardCharsets.UTF_8),
                        new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)));

        int sigAt = pathAndQuery.indexOf("sig=");
        String tampered = pathAndQuery.substring(0, sigAt) + "sig=deadbeef";
        mockMvc.perform(get(tampered))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadInvalidException.REASON));

        Instant expired = Instant.now().minus(Duration.ofMinutes(20));
        String expiredUrl = signature.redeemUrl("http://127.0.0.1:8080", TENANT_A, key, expired);
        String expiredPath = expiredUrl.substring(expiredUrl.indexOf(ObjectDownloadEndpoint.REDEEM_PATH));
        mockMvc.perform(get(expiredPath))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadInvalidException.REASON));
    }

    @Test
    void unregisteredLegacyEntityBlobKeyIsDeniedOnThisPath() throws Exception {
        String key = "legacy-" + UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO entity_blob
                  (blob_id, tenant_id, entity_key, record_id, field_name, content_type,
                   byte_size, storage_key, checksum_sha256, created_at)
                VALUES (?, ?, 'demo', 'r1', 'attachment', 'text/plain',
                        1, ?, '00', CURRENT_TIMESTAMP)
                """,
                key,
                TENANT_A,
                key);
        mockMvc.perform(issueOperator(TENANT_A, key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(ObjectDownloadDeniedException.REASON))
                .andExpect(jsonPath("$.url").doesNotExist());
        Integer descriptors = jdbc.queryForObject(
                "SELECT COUNT(*) FROM object_descriptor WHERE tenant_id = ? AND object_key = ?",
                Integer.class,
                TENANT_A,
                key);
        assertEquals(0, descriptors == null ? 0 : descriptors);
    }

    private String registerOwned() {
        return registrar.putRegistered(
                        TENANT_A,
                        "hybrid-asset",
                        "r-" + UUID.randomUUID(),
                        "attachment",
                        PAYLOAD,
                        "text/plain",
                        LocalOperatorSeeder.SUBJECT_ID,
                        LocalOperatorSeeder.IDENTITY_ID)
                .storageKey();
    }

    private void ensureTenant(String tenantId, String name) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, tenantId);
        if (n == null || n == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    tenantId,
                    name);
        }
    }

    private MockHttpServletRequestBuilder issueOperator(String tenantId, String objectKey) {
        return post(ObjectDownloadEndpoint.OPERATOR_ISSUE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody(tenantId, objectKey))
                .with(httpBasic(OPERATOR, OPERATOR_PASSWORD));
    }

    private MockHttpServletRequestBuilder issueEndUser(String accessToken, String tenantId, String objectKey) {
        return post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody(tenantId, objectKey))
                .header("Authorization", "Bearer " + accessToken);
    }

    private MockHttpServletRequestBuilder issueEndUserWithBasic(String tenantId, String objectKey) {
        return post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(issueBody(tenantId, objectKey))
                .with(httpBasic(OPERATOR, OPERATOR_PASSWORD));
    }

    private MockHttpServletRequestBuilder auditQuery(String tenantId, String action) {
        return get(JsonApi.BASE + "/audit")
                .param("tenantId", tenantId)
                .param("action", action)
                .with(httpBasic(OPERATOR, OPERATOR_PASSWORD));
    }

    private String issueOperatorOk(String tenantId, String objectKey) throws Exception {
        MvcResult result = mockMvc.perform(issueOperator(tenantId, objectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString())
                .andReturn();
        return objectMapper.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).path("url").asText();
    }

    private String issueEndUserOk(String accessToken, String tenantId, String objectKey) throws Exception {
        MvcResult result = mockMvc.perform(issueEndUser(accessToken, tenantId, objectKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString())
                .andReturn();
        return objectMapper.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8)).path("url").asText();
    }

    private static String issueBody(String tenantId, String objectKey) {
        return "{\"tenantId\":\"" + tenantId + "\",\"objectKey\":\"" + objectKey + "\"}";
    }

    private static boolean auditMentions(MvcResult result, String objectKey) {
        return responseBody(result).contains(objectKey);
    }

    /** True when any audit entry carries that tenant id. / 审计条目中出现该租户时为 true。 */
    private boolean auditHasTenant(MvcResult result, String tenantId) {
        var entries = objectMapper.readTree(responseBody(result)).path("entries");
        for (var entry : entries) {
            if (tenantId.equals(entry.path("tenantId").asText())) {
                return true;
            }
        }
        return false;
    }

    private static String responseBody(MvcResult result) {
        return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String propertyWithProfiles(String... profiles) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        if (profiles.length > 0) {
            environment.setActiveProfiles(profiles);
        }
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        addYaml(environment, loader, "application.yml", false);
        for (String profile : profiles) {
            addYaml(environment, loader, "application-" + profile + ".yml", true);
        }
        String value = environment.getProperty("platform.storage.type");
        assertTrue(value != null, "platform.storage.type must be set in loaded yaml");
        return value;
    }

    private static void addYaml(
            StandardEnvironment environment, YamlPropertySourceLoader loader, String name, boolean override)
            throws IOException {
        Resource resource = new ClassPathResource(name);
        assertTrue(resource.exists(), () -> "missing classpath resource " + name);
        List<PropertySource<?>> sources = loader.load(name, resource);
        for (int i = sources.size() - 1; i >= 0; i--) {
            PropertySource<?> source = sources.get(i);
            if (override) {
                environment.getPropertySources().addFirst(source);
            } else {
                environment.getPropertySources().addLast(source);
            }
        }
    }

    @TestConfiguration
    static class GuardConfiguration {
        @Bean
        TenantGuard tenantGuard() {
            return new DenyWhenTenantMissing();
        }
    }

    @TestConfiguration
    static class IsolationConfiguration {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        TransactionTemplate isolationTransactionTemplate(DataSource operatorTables) {
            return new TransactionTemplate(new DataSourceTransactionManager(operatorTables));
        }

        @Bean
        JdbcEndUserAccountStore endUserAccountStore(
                JdbcTemplate jdbc, TransactionTemplate isolationTransactionTemplate) {
            return new JdbcEndUserAccountStore(jdbc, isolationTransactionTemplate, Clock.systemUTC());
        }

        @Bean
        JdbcEndUserTokenStore endUserTokenStore(
                JdbcTemplate jdbc,
                TransactionTemplate isolationTransactionTemplate,
                EndUserAccountStore endUserAccountStore) {
            return new JdbcEndUserTokenStore(
                    jdbc,
                    isolationTransactionTemplate,
                    endUserAccountStore,
                    Clock.systemUTC(),
                    Duration.ofMinutes(30),
                    Duration.ofHours(8));
        }

        @Bean
        JdbcAdminReader jdbcAdminReader(JdbcTemplate jdbc) {
            return new JdbcAdminReader(jdbc);
        }

        @Bean
        ObjectAccessAudit objectAccessAudit(AuditPort auditPort, Clock clock) {
            return new ObjectAccessAudit(auditPort, clock);
        }

        @Bean
        JdbcObjectGrantStore objectGrantStore(JdbcTemplate jdbc, Clock clock, ObjectAccessAudit objectAccessAudit) {
            return new JdbcObjectGrantStore(jdbc, clock, objectAccessAudit);
        }

        @Bean
        ObjectAccessAuthorizer objectAccessAuthorizer(
                JdbcObjectGrantStore objectGrantStore, JdbcTemplate jdbc, Clock clock) {
            return new ObjectAccessAuthorizer(objectGrantStore, jdbc, clock);
        }

        @Bean
        ObjectStorage objectStorage() throws Exception {
            return new LocalDirectoryObjectStorage(Files.createTempDirectory("wp35-objects"));
        }

        @Bean
        EntityBlobStore entityBlobStore(JdbcTemplate jdbc, ObjectStorage objectStorage, Clock clock) {
            return new JdbcEntityBlobStore(jdbc, objectStorage, clock);
        }

        @Bean
        AuthorizedObjectRegistrar authorizedObjectRegistrar(
                EntityBlobStore entityBlobStore,
                JdbcObjectGrantStore objectGrantStore,
                ObjectAccessAudit objectAccessAudit) {
            return new AuthorizedObjectRegistrar(entityBlobStore, objectGrantStore, objectAccessAudit);
        }

        @Bean
        ObjectDownloadProperties objectDownloadProperties() {
            return new ObjectDownloadProperties(
                    Duration.ofMinutes(15), Duration.ofHours(1), "http://127.0.0.1:8080", SIGNING_KEY);
        }

        @Bean
        LocalDownloadSignature localDownloadSignature(ObjectDownloadProperties properties) {
            return new LocalDownloadSignature(properties.signingKey());
        }

        @Bean
        ObjectDownloadUrlIssuer objectDownloadUrlIssuer(
                LocalDownloadSignature localDownloadSignature, ObjectDownloadProperties properties, Clock clock) {
            return new LocalHmacDownloadIssuer(localDownloadSignature, properties.publicBaseUrl(), clock);
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

        @Bean
        JdbcOrganizationStore jdbcOrganizationStore(JdbcTemplate jdbc) {
            return new JdbcOrganizationStore(jdbc);
        }
    }
}
