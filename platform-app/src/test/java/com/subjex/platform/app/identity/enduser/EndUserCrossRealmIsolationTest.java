package com.subjex.platform.app.identity.enduser;

import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR;
import static com.subjex.platform.app.security.OperatorDirectoryTestConfiguration.OPERATOR_PASSWORD;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.jdbc.JdbcAuditPort;
import com.subjex.platform.app.idp.IdpProvidersEndpoint;
import com.subjex.platform.app.idp.MeIdpLinkEndpoint;
import com.subjex.platform.app.inbox.EndUserInboxEndpoint;
import com.subjex.platform.app.inbox.InboxPathRetiredException;
import com.subjex.platform.app.security.EndUserRealmGateFilter;
import com.subjex.platform.app.security.OperatorAuthEndpoint;
import com.subjex.platform.app.security.OperatorDirectoryTestConfiguration;
import com.subjex.platform.app.security.OperatorManagementEndpoint;
import com.subjex.platform.app.security.OperatorMfaEndpoint;
import com.subjex.platform.app.security.OperatorOidcEndpoint;
import com.subjex.platform.app.security.OperatorSelfEndpoint;
import com.subjex.platform.app.security.PlatformSecurityConfiguration;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.security.TenantManagementEndpoint;
import com.subjex.platform.app.web.PlatformExceptionAdvice;
import com.subjex.platform.contract.tenant.DenyWhenTenantMissing;
import com.subjex.platform.contract.tenant.TenantGuard;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EndUserCrossRealmIsolationTest — purpose/gate WP2-5: formal cross-realm isolation.
 * End-user tokens cannot call operator console management APIs (403
 * {@code operator-authentication-required}); operator tokens/HTTP Basic cannot act as
 * end-user authenticators on bind/unbind or as {@code eu.rt.} on end-user refresh.
 * <p>
 * 目的/门禁 WP2-5：跨领域隔离。终端令牌不得调用操作员控制台管理接口（403）；
 * 操作员令牌/HTTP Basic 不得充当终端认证器，也不得被终端刷新当成 {@code eu.rt.}。
 */
@WebMvcTest(
        controllers = {
            OperatorAuthEndpoint.class,
            OperatorSelfEndpoint.class,
            OperatorManagementEndpoint.class,
            OperatorMfaEndpoint.class,
            OperatorOidcEndpoint.class,
            TenantManagementEndpoint.class,
            MeIdpLinkEndpoint.class,
            IdpProvidersEndpoint.class,
            EndUserAuthEndpoint.class,
            EndUserPhoneAuthEndpoint.class,
            EndUserWeChatAuthEndpoint.class,
            EndUserInboxEndpoint.class
        })
@Import({
    PlatformSecurityConfiguration.class,
    OperatorDirectoryTestConfiguration.class,
    PlatformExceptionAdvice.class,
    EndUserCrossRealmIsolationTest.GuardConfiguration.class,
    EndUserCrossRealmIsolationTest.EndUserIsolationConfiguration.class
})
@TestPropertySource(properties = "platform.auth.http-basic-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EndUserCrossRealmIsolationTest {

    private static final String TENANT_A = "tenant-a";

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

    private String endUserAccess;
    private String endUserRefresh;
    private String operatorAccess;
    private String operatorRefresh;

    @BeforeEach
    void issueTokens() throws Exception {
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, TENANT_A)
                == 0) {
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    "Tenant A");
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'SUSPENDED')",
                    "frozen",
                    "Frozen");
        }
        EndUserRecord user = accounts.findByPhone("13800138888").orElseGet(() -> {
            EndUserRecord created = accounts.provision(TENANT_A, "Ada");
            accounts.bindPhone(created.account().accountId(), "13800138888");
            return created;
        });
        JdbcEndUserTokenStore.IssuedTokens issued =
                endUserTokens.issue(user.account().accountId(), TENANT_A, "JUnit", "127.0.0.1");
        endUserAccess = issued.accessToken();
        endUserRefresh = issued.refreshToken();
        assertThat(endUserAccess).startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX);
        assertThat(endUserRefresh).startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX);

        JsonNode operator = loginOperator();
        operatorAccess = operator.path("accessToken").asText();
        operatorRefresh = operator.path("refreshToken").asText();
        assertThat(operatorAccess).isNotBlank();
        assertThat(operatorAccess).doesNotStartWith("eu.");
    }

    static Stream<Route> operatorManagementRoutes() {
        return Stream.of(
                Route.get(OperatorManagementEndpoint.PATH),
                Route.get(OperatorSelfEndpoint.PATH),
                Route.post(OperatorManagementEndpoint.PATH + "/me/password"),
                Route.get(OperatorMfaEndpoint.PATH),
                Route.post(OperatorMfaEndpoint.PATH + "/totp/start"),
                Route.get(TenantManagementEndpoint.PATH),
                Route.post(TenantManagementEndpoint.PATH),
                Route.get("/api/v1/ui-permissions"),
                Route.get("/api/v1/ui-permissions/draft"),
                Route.post("/api/v1/ui-permissions/draft/promote"),
                Route.get("/api/v1/oauth/clients"),
                Route.get("/api/v1/inbox"),
                Route.get("/api/v1/inbox/entries"),
                Route.post("/api/v1/inbox/system"),
                Route.get("/api/v1/questionnaires"),
                Route.post("/api/v1/questionnaires"),
                Route.post("/api/v1/declarations/demo/1.0.0/promote"),
                Route.get(MeIdpLinkEndpoint.PATH),
                Route.post(OperatorAuthEndpoint.PATH + "/login"),
                Route.post(OperatorAuthEndpoint.PATH + "/refresh"),
                Route.post(OperatorAuthEndpoint.PATH + "/logout"),
                Route.post(OperatorAuthEndpoint.PATH + "/mfa/verify"),
                Route.post(OperatorOidcEndpoint.PATH + "/start"),
                Route.post(OperatorOidcEndpoint.PATH + "/callback"),
                Route.get(OperatorOidcEndpoint.PATH + "/status"),
                Route.post(IdpProvidersEndpoint.PATH + "/wechat/start"),
                Route.post(IdpProvidersEndpoint.PATH + "/wechat/callback"));
    }

    @ParameterizedTest
    @MethodSource("operatorManagementRoutes")
    void endUserAccessTokenIsForbiddenOnOperatorRoutes(Route route) throws Exception {
        mockMvc.perform(route.request()
                        .header("Authorization", "Bearer " + endUserAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserRealmGateFilter.REASON));
    }

    @Test
    void leftoverMeInboxPathIsGoneForEndUserAndOperator() throws Exception {
        // Retired path is not a second working inbox; both realms get 410, not mailbox data.
        // 旧路径不是第二套可用收件箱；终端与操作员令牌都得到 410，而不是信件内容。
        mockMvc.perform(get(EndUserInboxEndpoint.BASE + "/entries")
                        .header("Authorization", "Bearer " + endUserAccess)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value(InboxPathRetiredException.REASON))
                .andExpect(jsonPath("$.use").value(InboxPathRetiredException.USE));
        mockMvc.perform(get(EndUserInboxEndpoint.BASE + "/unread-count")
                        .header("Authorization", "Bearer " + operatorAccess)
                        .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.reason").value(InboxPathRetiredException.REASON));
    }

    @Test
    void endUserRefreshAndLogoutRemainAllowed() throws Exception {
        MvcResult refreshed = mockMvc.perform(post(EndUserAuthEndpoint.PATH + "/refresh")
                        .header("Authorization", "Bearer " + endUserAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + endUserRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andReturn();
        JsonNode body = objectMapper.readTree(refreshed.getResponse().getContentAsString());
        String newRefresh = body.path("refreshToken").asText();
        assertThat(newRefresh).startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX);
        mockMvc.perform(post(EndUserAuthEndpoint.PATH + "/logout")
                        .header("Authorization", "Bearer " + body.path("accessToken").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void operatorTokenAndHttpBasicAreRejectedAsEndUserAuthenticators() throws Exception {
        for (String path : List.of(
                EndUserPhoneAuthEndpoint.PATH + "/bind",
                EndUserPhoneAuthEndpoint.PATH + "/unbind",
                EndUserWeChatAuthEndpoint.PATH + "/bind",
                EndUserWeChatAuthEndpoint.PATH + "/unbind")) {
            mockMvc.perform(post(path)
                            .header("Authorization", "Bearer " + operatorAccess)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason")
                            .value(EndUserPhoneAuthService.EndUserAuthenticationRequiredException.REASON));
            mockMvc.perform(post(path)
                            .with(httpBasic(OPERATOR, OPERATOR_PASSWORD))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason")
                            .value(EndUserPhoneAuthService.EndUserAuthenticationRequiredException.REASON));
        }
    }

    @Test
    void operatorRefreshTokenIsNotAcceptedAsEndUserRefresh() throws Exception {
        mockMvc.perform(post(EndUserAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + operatorRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void endUserRefreshTokenIsForbiddenOnOperatorRefreshAndLogout() throws Exception {
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + endUserRefresh + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserRealmGateFilter.REASON));
        mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + endUserRefresh + "\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserRealmGateFilter.REASON));
    }

    @Test
    void bearerFilterSplitsRealmsBothDirections() throws Exception {
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + operatorAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loginName").value(OPERATOR));
        mockMvc.perform(get(OperatorSelfEndpoint.PATH).header("Authorization", "Bearer " + endUserAccess))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value(EndUserRealmGateFilter.REASON));
        // An operator token must not be parsed as an end-user principal on bind.
        // 操作员令牌不得在绑定接口被解析为终端主体。
        mockMvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/bind")
                        .header("Authorization", "Bearer " + operatorAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason")
                        .value(EndUserPhoneAuthService.EndUserAuthenticationRequiredException.REASON));
    }

    @Test
    void endUserTenantScopedLoginStaysFailClosed() throws Exception {
        mockMvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138888\",\"code\":\"000000\",\"tenantId\":\"platform\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"13800138888\",\"code\":\"000000\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TenantEnforcementFilter.TENANT_HEADER, "missing-tenant")
                        .content("{\"phone\":\"13800138888\",\"code\":\"000000\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reason").value("tenant-not-found"));
        mockMvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(TenantEnforcementFilter.TENANT_HEADER, "frozen")
                        .content("{\"phone\":\"13800138888\",\"code\":\"000000\"}"))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", Integer.class, "missing-tenant"))
                .isZero();
        assertThat(endUserAccess).startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX);
    }

    private JsonNode loginOperator() throws Exception {
        MvcResult result = mockMvc.perform(post(OperatorAuthEndpoint.PATH + "/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginName\":\"" + OPERATOR + "\",\"password\":\"" + OPERATOR_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    record Route(HttpMethod method, String path) {
        static Route get(String path) {
            return new Route(HttpMethod.GET, path);
        }

        static Route post(String path) {
            return new Route(HttpMethod.POST, path);
        }

        MockHttpServletRequestBuilder request() {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(method, path);
        }

        @Override
        public String toString() {
            return method.name() + " " + path;
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
    static class EndUserIsolationConfiguration {
        @Bean
        TransactionTemplate endUserTransactionTemplate(DataSource operatorTables) {
            return new TransactionTemplate(new DataSourceTransactionManager(operatorTables));
        }

        @Bean
        JdbcEndUserAccountStore endUserAccountStore(
                JdbcTemplate jdbc, TransactionTemplate endUserTransactionTemplate) {
            return new JdbcEndUserAccountStore(jdbc, endUserTransactionTemplate, Clock.systemUTC());
        }

        @Bean
        JdbcEndUserTokenStore endUserTokenStore(
                JdbcTemplate jdbc,
                TransactionTemplate endUserTransactionTemplate,
                EndUserAccountStore endUserAccountStore) {
            return new JdbcEndUserTokenStore(
                    jdbc,
                    endUserTransactionTemplate,
                    endUserAccountStore,
                    Clock.systemUTC(),
                    Duration.ofMinutes(30),
                    Duration.ofHours(8));
        }

        @Bean
        MemoryVerificationCodePort endUserVerificationCodePort() {
            return new MemoryVerificationCodePort(
                    Clock.systemUTC(), Duration.ofMinutes(5), true, new DevVerificationCodeSender());
        }

        @Bean
        JdbcEndUserLoginLockout endUserLoginLockout(
                JdbcTemplate jdbc, TransactionTemplate endUserTransactionTemplate) {
            return new JdbcEndUserLoginLockout(
                    jdbc, endUserTransactionTemplate, Clock.systemUTC(), 5, Duration.ofMinutes(15));
        }

        @Bean
        EndUserPhoneAuthService endUserPhoneAuthService(
                EndUserAccountStore endUserAccountStore,
                JdbcEndUserTokenStore endUserTokenStore,
                VerificationCodePort endUserVerificationCodePort,
                JdbcEndUserLoginLockout endUserLoginLockout,
                JdbcTemplate jdbc) {
            return new EndUserPhoneAuthService(
                    endUserAccountStore,
                    endUserTokenStore,
                    endUserVerificationCodePort,
                    endUserLoginLockout,
                    jdbc,
                    new JdbcAuditPort(jdbc),
                    Clock.systemUTC());
        }

        @Bean
        EndUserWeChatAssertion endUserWeChatAssertion() {
            return new DevEndUserWeChatAssertion();
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
    }
}
