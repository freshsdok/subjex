package com.subjex.platform.app.storage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.EndUserObjectGrantEndpoint;
import com.subjex.platform.app.identity.enduser.EndUserPrincipal;
import com.subjex.platform.app.identity.enduser.EndUserRecord;
import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.organization.JdbcOrganizationStore;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.PolicyEngineFactory;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * ObjectGrantManagementTest — HTTP list/grant/revoke for owners and operators (cut 2).
 * Gates: subject and org grants; expiry; owner-only end-user; operator permission and tenant grant;
 * cross-tenant denial; revoked or expired grant can no longer issue a download.
 * <p>
 * 目的：所有者与操作员的列出/授予/撤销 HTTP。门禁：主体与组织授权、过期、终端仅限所有者、
 * 操作员权限与租户授权、跨租户拒绝；撤销或过期后不能再签发下载。
 */
class ObjectGrantManagementTest {

    private static final String TENANT_A = "tenant-grant-a";
    private static final String TENANT_B = "tenant-grant-b";
    private static final Instant T0 = Instant.parse("2026-11-28T08:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORG = "org-grant-one";

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void ownerCanGrantToSubjectAndRevokeStopsDownloadIssue(H2PlatformTables.Mode mode) throws Exception {
        Fixture fx = Fixture.open(mode);
        String objectId = fx.registerOwned(fx.alice, "file-alice");

        fx.mvc.perform(get(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object.objectId").value(objectId))
                .andExpect(jsonPath("$.grants.length()").value(0));

        fx.mvc.perform(post(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("SUBJECT", fx.bob.subject().subjectId(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granteeKind").value("SUBJECT"))
                .andExpect(jsonPath("$.granteeId").value(fx.bob.subject().subjectId()))
                .andExpect(jsonPath("$.effective").value(true));

        fx.mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("file-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isNotEmpty());

        MvcResult listed = fx.mvc.perform(get(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(1))
                .andReturn();
        String grantId = MAPPER.readTree(listed.getResponse().getContentAsString())
                .path("grants")
                .get(0)
                .path("grantId")
                .asText();

        fx.mvc.perform(delete(EndUserObjectGrantEndpoint.GRANT_PATH, objectId, grantId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNoContent());

        fx.mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("file-alice")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void ownerCanGrantToOrganizationAndExpiredGrantCannotIssue(H2PlatformTables.Mode mode) throws Exception {
        Fixture fx = Fixture.open(mode);
        String objectId = fx.registerOwned(fx.alice, "file-org");
        fx.bindOrg(ORG, fx.bob.subject().subjectId());

        Instant expires = T0.plusSeconds(30);
        fx.mvc.perform(post(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("ORGANIZATION", ORG, expires)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granteeKind").value("ORGANIZATION"))
                .andExpect(jsonPath("$.effective").value(true));

        fx.mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("file-org")))
                .andExpect(status().isOk());

        fx.now.set(T0.plusSeconds(31));
        fx.mvc.perform(get(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants[0].effective").value(false));
        fx.mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("file-org")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void endUserMustOwnTheObjectAndExplicitDestinationRulesHold(H2PlatformTables.Mode mode) throws Exception {
        Fixture fx = Fixture.open(mode);
        String objectId = fx.registerOwned(fx.alice, "file-alice");
        fx.mvc.perform(get(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());
        fx.mvc.perform(post(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("SUBJECT", fx.bob.subject().subjectId(), null)))
                .andExpect(status().isForbidden());

        fx.mvc.perform(get(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());

        fx.mvc.perform(post(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("SUBJECT", fx.carolInB.subject().subjectId(), null)))
                .andExpect(status().isBadRequest());

        fx.mvc.perform(post(EndUserObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asEndUser(fx.alice))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("SUBJECT", fx.bob.subject().subjectId(), T0.minusSeconds(1))))
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void operatorListGrantRevokeHonoursPermissionAndTenant(H2PlatformTables.Mode mode) throws Exception {
        Fixture fx = Fixture.open(mode);
        String objectId = fx.registerOwned(fx.alice, "file-ops");

        fx.mvc.perform(get(OperatorObjectGrantEndpoint.PATH)
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(1))
                .andExpect(jsonPath("$.objects[0].objectId").value(objectId));

        fx.mvc.perform(get(OperatorObjectGrantEndpoint.PATH)
                        .with(asOperator(fx.reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.objects.length()").value(1));

        fx.mvc.perform(post(OperatorObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asOperator(fx.reader))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("SUBJECT", fx.bob.subject().subjectId(), null)))
                .andExpect(status().isForbidden());

        fx.mvc.perform(post(OperatorObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("SUBJECT", fx.bob.subject().subjectId(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granteeId").value(fx.bob.subject().subjectId()));

        fx.mvc.perform(get(OperatorObjectGrantEndpoint.PATH)
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
        fx.mvc.perform(get(OperatorObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_B))
                .andExpect(status().isForbidden());
        fx.mvc.perform(get(OperatorObjectGrantEndpoint.PATH)
                        .with(asOperator(fx.outsider))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());
        fx.mvc.perform(get(OperatorObjectGrantEndpoint.PATH)
                        .with(asOperator(fx.noPermission))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isForbidden());

        JsonNode grants = MAPPER.readTree(fx.mvc.perform(get(OperatorObjectGrantEndpoint.GRANTS_PATH, objectId)
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(1))
                .andReturn()
                .getResponse()
                .getContentAsString());
        String grantId = grants.path("grants").get(0).path("grantId").asText();
        fx.mvc.perform(delete(OperatorObjectGrantEndpoint.GRANT_PATH, objectId, grantId)
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNoContent());
        fx.mvc.perform(post(ObjectDownloadEndpoint.END_USER_ISSUE_PATH)
                        .with(asEndUser(fx.bob))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(issueBody("file-ops")))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void explicitPastExpiryIsRejectedAndMissingObjectIsNotFound(H2PlatformTables.Mode mode) throws Exception {
        Fixture fx = Fixture.open(mode);
        fx.mvc.perform(get(OperatorObjectGrantEndpoint.GRANTS_PATH, "missing-object")
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
        String objectId = fx.registerOwned(fx.alice, "file-missing");
        fx.mvc.perform(delete(OperatorObjectGrantEndpoint.GRANT_PATH, objectId, "missing-grant")
                        .with(asOperator(fx.manager))
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isNotFound());
    }

    private static String grantBody(String kind, String granteeId, Instant expiresAt) {
        String expiry = expiresAt == null ? "null" : "\"" + expiresAt + "\"";
        return "{\"granteeKind\":\"" + kind + "\",\"granteeId\":\"" + granteeId + "\",\"expiresAt\":" + expiry + "}";
    }

    private static String issueBody(String objectKey) {
        return "{\"tenantId\":\"" + TENANT_A + "\",\"objectKey\":\"" + objectKey + "\"}";
    }

    private static RequestPostProcessor asEndUser(EndUserRecord record) {
        return asPrincipal(new EndUserPrincipal(
                record.account().accountId(),
                record.subject().subjectId(),
                record.account().loginName(),
                true));
    }

    private static RequestPostProcessor asOperator(OperatorPrincipal operator) {
        return asPrincipal(operator);
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }

    private static final class Fixture {
        private final JdbcTemplate jdbc;
        private final JdbcObjectGrantStore store;
        private final JdbcOrganizationStore orgs;
        private final MockMvc mvc;
        private final AtomicReference<Instant> now;
        private final EndUserRecord alice;
        private final EndUserRecord bob;
        private final EndUserRecord carolInB;
        private final OperatorPrincipal manager;
        private final OperatorPrincipal reader;
        private final OperatorPrincipal outsider;
        private final OperatorPrincipal noPermission;

        private Fixture(
                JdbcTemplate jdbc,
                JdbcObjectGrantStore store,
                JdbcOrganizationStore orgs,
                MockMvc mvc,
                AtomicReference<Instant> now,
                EndUserRecord alice,
                EndUserRecord bob,
                EndUserRecord carolInB,
                OperatorPrincipal manager,
                OperatorPrincipal reader,
                OperatorPrincipal outsider,
                OperatorPrincipal noPermission) {
            this.jdbc = jdbc;
            this.store = store;
            this.orgs = orgs;
            this.mvc = mvc;
            this.now = now;
            this.alice = alice;
            this.bob = bob;
            this.carolInB = carolInB;
            this.manager = manager;
            this.reader = reader;
            this.outsider = outsider;
            this.noPermission = noPermission;
        }

        private static Fixture open(H2PlatformTables.Mode mode) {
            DataSource source = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    TENANT_A);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_B,
                    TENANT_B);
            AtomicReference<Instant> now = new AtomicReference<>(T0);
            Clock clock = new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return now.get();
                }
            };
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
            JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
            ObjectAccessAudit audit = new ObjectAccessAudit(new JdbcAuditPort(jdbc), clock);
            JdbcObjectGrantStore store = new JdbcObjectGrantStore(jdbc, clock, audit);
            JdbcOrganizationStore orgs = new JdbcOrganizationStore(jdbc);
            JdbcOperatorTenantAccess tenantAccess = new JdbcOperatorTenantAccess(jdbc, tx);
            ObjectGrantManagementService service = new ObjectGrantManagementService(
                    store,
                    jdbc,
                    accounts,
                    tenantAccess,
                    PolicyEngineFactory.create("sql", new DenyWhenTenantMissing(), tenantAccess),
                    clock);
            ObjectAccessAuthorizer authorizer = new ObjectAccessAuthorizer(store, jdbc, clock);
            ObjectDownloadProperties properties = new ObjectDownloadProperties(
                    Duration.ofMinutes(15), Duration.ofHours(1), "http://127.0.0.1:8080", "test-hmac-key");
            LocalDownloadSignature signature = new LocalDownloadSignature("test-hmac-key");
            ObjectDownloadLinkService downloads = new ObjectDownloadLinkService(
                    authorizer,
                    new LocalHmacDownloadIssuer(signature, properties.publicBaseUrl(), clock),
                    signature,
                    new LocalDirectoryObjectStorage(java.nio.file.Path.of("build/test-object-grants")),
                    properties,
                    clock,
                    audit);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(
                            new OperatorObjectGrantEndpoint(service),
                            new EndUserObjectGrantEndpoint(service),
                            new ObjectDownloadEndpoint(downloads))
                    .setControllerAdvice(new PlatformExceptionAdvice(null))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter())
                    .build();
            EndUserRecord alice = accounts.provision(TENANT_A, "Alice");
            EndUserRecord bob = accounts.provision(TENANT_A, "Bob");
            EndUserRecord carolInB = accounts.provision(TENANT_B, "Carol");
            OperatorPrincipal manager = operator("mgr", "object.grant.read", "object.grant");
            OperatorPrincipal reader = operator("reader", "object.grant.read");
            OperatorPrincipal outsider = operator("out", "object.grant.read", "object.grant");
            OperatorPrincipal noPermission = operator("none");
            grantTenant(jdbc, manager, TENANT_A);
            grantTenant(jdbc, reader, TENANT_A);
            grantTenant(jdbc, noPermission, TENANT_A);
            grantTenant(jdbc, outsider, TENANT_B);
            return new Fixture(
                    jdbc, store, orgs, mvc, now, alice, bob, carolInB, manager, reader, outsider, noPermission);
        }

        private String registerOwned(EndUserRecord owner, String objectKey) {
            return store.register(TENANT_A, objectKey, owner.subject().subjectId()).objectId();
        }

        private void bindOrg(String organizationId, String memberSubjectId) {
            orgs.upsertOrganization(organizationId, "Org One", JdbcOrganizationStore.STATE_ACTIVE);
            orgs.upsertTenantOrganization(TENANT_A, organizationId, JdbcOrganizationStore.STATE_ACTIVE);
            orgs.upsertMembership(memberSubjectId, organizationId, JdbcOrganizationStore.STATE_ACTIVE);
        }

        private static void grantTenant(JdbcTemplate jdbc, OperatorPrincipal operator, String tenantId) {
            jdbc.update(
                    "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                    operator.subjectId(),
                    tenantId);
        }

        private static OperatorPrincipal operator(String slug, String... permissions) {
            return new OperatorPrincipal(
                    "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
        }
    }
}
