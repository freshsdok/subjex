package com.subjex.platform.app.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.identity.enduser.JdbcEndUserAccountStore;
import com.subjex.platform.app.identity.enduser.JdbcEndUserTokenStore;
import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.JdbcOperatorDirectory;
import com.subjex.platform.app.security.JdbcOperatorTenantAccess;
import com.subjex.platform.app.security.JdbcOperatorTokenStore;
import com.subjex.platform.app.security.OperatorActionAudit;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.OperatorTenantNotGrantedException;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SessionListTest — current logins from refresh-token families; revoke; tenant isolation.
 * 当前登录来自刷新令牌族；吊销；按租户隔离。
 */
class SessionListTest {

    private static final Instant NOW = Instant.parse("2026-11-23T00:00:00Z");
    private static final String TENANT_A = "tenant-sess-a";
    private static final String TENANT_B = "tenant-sess-b";

    private JdbcTemplate jdbc;
    private SessionListService service;
    private MockMvc mvc;
    private OperatorPrincipal admin;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        DataSource ds = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        JdbcOperatorTokenStore operatorTokens = new JdbcOperatorTokenStore(
                jdbc,
                tx,
                new JdbcOperatorDirectory(jdbc),
                clock,
                Duration.ofMinutes(15),
                Duration.ofHours(8));
        JdbcEndUserTokenStore endUserTokens = new JdbcEndUserTokenStore(
                jdbc,
                tx,
                new JdbcEndUserAccountStore(jdbc, tx, clock),
                clock,
                Duration.ofMinutes(15),
                Duration.ofHours(8));
        OperatorActionAudit audit = new OperatorActionAudit(new JdbcAuditPort(jdbc), clock);
        service = new SessionListService(
                jdbc, operatorTokens, endUserTokens, new JdbcOperatorTenantAccess(jdbc, tx), audit, clock);
        mvc = MockMvcBuilders.standaloneSetup(new SessionListEndpoint(service))
                .setControllerAdvice(new PlatformExceptionAdvice(null))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        admin = operator("admin", "admin.read", "operator.manage");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        jdbc.update(
                "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                admin.subjectId(),
                TENANT_A);
        insertOperatorAccount("account-alice", "alice", "sub-alice");
        insertOperatorRefresh("family-alice", "account-alice", "sub-alice", "Mozilla/5.0", "203.0.113.10", NOW.plus(Duration.ofHours(8)), null);
        insertOperatorRefresh("family-old", "account-alice", "sub-alice", "OldClient", "203.0.113.11", NOW.minus(Duration.ofHours(1)), null);
        insertOperatorRefresh("family-revoked", "account-alice", "sub-alice", "RevokedClient", "203.0.113.12", NOW.plus(Duration.ofHours(8)), NOW.minus(Duration.ofMinutes(5)));
        insertEndUser("eu-acc-a", "eu-sub-a", "eu-id-a", TENANT_A, "Pat Lee");
        insertEndUser("eu-acc-b", "eu-sub-b", "eu-id-b", TENANT_B, "Other User");
        insertEndUserRefresh("family-eu-a", "eu-acc-a", "eu-sub-a", "uni-app", "198.51.100.7", NOW.plus(Duration.ofHours(8)), null);
        insertEndUserRefresh("family-eu-b", "eu-acc-b", "eu-sub-b", "uni-app", "198.51.100.8", NOW.plus(Duration.ofHours(8)), null);
        insertEndUserAccess("family-eu-a", "eu-acc-a", "eu-sub-a");
        insertOperatorAccess("family-alice", "account-alice", "sub-alice");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listsOnlyCurrentOperatorLoginsAndOmitsHashes() throws Exception {
        var result = mvc.perform(get(SessionListEndpoint.PATH + "/operators").with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realm").value("operator"))
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].displayName").value("alice"))
                .andExpect(jsonPath("$.sessions[0].userAgent").value("Mozilla/5.0"))
                .andExpect(jsonPath("$.sessions[0].clientIp").value("203.0.113.10"))
                .andExpect(jsonPath("$.sessions[0].familyId").value("family-alice"))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body.toLowerCase(Locale.ROOT)).doesNotContain("token_hash", "tokenhash", "password");
        JsonNode row = mapper.readTree(body).get("sessions").get(0);
        assertThat(row.has("tokenHash")).isFalse();
        assertThat(row.has("refreshToken")).isFalse();
    }

    @Test
    void revokeRemovesOperatorFamilyIncludingAccessToken() throws Exception {
        mvc.perform(post(SessionListEndpoint.PATH + "/operators/family-alice/revoke").with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        mvc.perform(get(SessionListEndpoint.PATH + "/operators").with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(0));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operator_refresh_token WHERE family_id = ? AND revoked_at IS NULL",
                        Integer.class,
                        "family-alice"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operator_access_token WHERE family_id = ? AND revoked_at IS NULL",
                        Integer.class,
                        "family-alice"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_entry WHERE action_name = ?",
                        Integer.class,
                        SessionListService.AUDIT_REVOKE_OPERATOR))
                .isEqualTo(1);
        mvc.perform(post(SessionListEndpoint.PATH + "/operators/family-missing/revoke").with(asPrincipal(admin)))
                .andExpect(status().isNotFound());
    }

    @Test
    void endUserListIsTenantScopedAndRevokeDoesNotCrossTenant() throws Exception {
        mvc.perform(get(SessionListEndpoint.PATH + "/end-users").queryParam("tenantId", TENANT_A).with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessions.length()").value(1))
                .andExpect(jsonPath("$.sessions[0].displayName").value("Pat Lee"))
                .andExpect(jsonPath("$.sessions[0].tenantId").value(TENANT_A))
                .andExpect(jsonPath("$.sessions[0].clientIp").value("198.51.100.7"));
        assertThatThrownBy(() -> service.listEndUsers(admin, TENANT_B, null))
                .isInstanceOf(OperatorTenantNotGrantedException.class);
        assertThatThrownBy(() -> service.listEndUsers(admin, "  ", null)).isInstanceOf(TenantMissingException.class);
        mvc.perform(post(SessionListEndpoint.PATH + "/end-users/family-eu-b/revoke")
                        .queryParam("tenantId", TENANT_A)
                        .with(asPrincipal(admin)))
                .andExpect(status().isNotFound());
        mvc.perform(post(SessionListEndpoint.PATH + "/end-users/family-eu-a/revoke")
                        .queryParam("tenantId", TENANT_A)
                        .with(asPrincipal(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revoked").value(true));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM end_user_refresh_token WHERE family_id = ? AND revoked_at IS NULL",
                        Integer.class,
                        "family-eu-a"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM end_user_access_token WHERE family_id = ? AND revoked_at IS NULL",
                        Integer.class,
                        "family-eu-a"))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM end_user_refresh_token WHERE family_id = ? AND revoked_at IS NULL",
                        Integer.class,
                        "family-eu-b"))
                .isEqualTo(1);
    }

    private void insertOperatorAccount(String accountId, String login, String subjectId) {
        jdbc.update(
                "INSERT INTO account (account_id, login_name, account_state) VALUES (?, ?, 'ACTIVE')",
                accountId,
                login);
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                subjectId,
                login);
    }

    private void insertOperatorRefresh(
            String familyId, String accountId, String subjectId, String ua, String ip, Instant expires, Instant revoked) {
        jdbc.update(
                """
                INSERT INTO operator_refresh_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at, user_agent, client_ip)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "refresh-" + familyId,
                "hash-" + familyId,
                subjectId,
                accountId,
                familyId,
                java.sql.Timestamp.from(expires),
                revoked == null ? null : java.sql.Timestamp.from(revoked),
                ua,
                ip);
    }

    private void insertOperatorAccess(String familyId, String accountId, String subjectId) {
        jdbc.update(
                """
                INSERT INTO operator_access_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """,
                "access-" + familyId,
                "ahash-" + familyId,
                subjectId,
                accountId,
                familyId,
                java.sql.Timestamp.from(NOW.plus(Duration.ofMinutes(15))));
    }

    private void insertEndUser(String accountId, String subjectId, String identityId, String tenantId, String name) {
        jdbc.update(
                "INSERT INTO account (account_id, login_name, account_state) VALUES (?, ?, 'ACTIVE')",
                accountId,
                "eu:" + accountId);
        jdbc.update(
                "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                subjectId,
                name);
        jdbc.update(
                """
                INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                identityId,
                accountId,
                subjectId,
                tenantId);
    }

    private void insertEndUserRefresh(
            String familyId, String accountId, String subjectId, String ua, String ip, Instant expires, Instant revoked) {
        jdbc.update(
                """
                INSERT INTO end_user_refresh_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at, user_agent, client_ip)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                "refresh-" + familyId,
                "hash-" + familyId,
                subjectId,
                accountId,
                familyId,
                java.sql.Timestamp.from(expires),
                revoked == null ? null : java.sql.Timestamp.from(revoked),
                ua,
                ip);
    }

    private void insertEndUserAccess(String familyId, String accountId, String subjectId) {
        jdbc.update(
                """
                INSERT INTO end_user_access_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """,
                "access-" + familyId,
                "ahash-" + familyId,
                subjectId,
                accountId,
                familyId,
                java.sql.Timestamp.from(NOW.plus(Duration.ofMinutes(15))));
    }

    private static OperatorPrincipal operator(String slug, String... permissions) {
        return new OperatorPrincipal(
                "op-" + slug, "hash", "id-" + slug, "sub-" + slug, Set.of(permissions), true);
    }

    private static RequestPostProcessor asPrincipal(Object principal) {
        return request -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
            SecurityContextHolder.setContext(context);
            return request;
        };
    }
}
