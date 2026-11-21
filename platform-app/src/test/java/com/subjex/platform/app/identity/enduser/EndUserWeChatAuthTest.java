package com.subjex.platform.app.identity.enduser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.idp.WeChatProperties;
import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EndUserWeChatAuthTest — purpose/gate WP2-4: first WeChat login auto-provisions in an ACTIVE
 * tenant, second login reuses the account, missing/disabled/{@code platform} tenants fail closed,
 * bind/unbind and last-authenticator protection, operator IdP/token/lockout tables stay empty,
 * unlinked-deny is not applied, unconfigured prod fails closed.
 * <p>
 * 目的/门禁 WP2-4：首次微信登录在 ACTIVE 租户自动开户，再次登录复用账号，缺失/停用/{@code platform}
 * 失败关闭，绑定解绑与最后认证器保护，操作员表保持为空，不套用「未绑定即拒绝」，未配置则失败关闭。
 */
class EndUserWeChatAuthTest {

    private static final String TENANT_A = "tenant-a";
    private static final String JS_ADA = "dev:ada-openid:ada-union";
    private static final String ADA_SUBJECT = "ada-union";
    private static final String JS_BEA = "dev:bea-openid";
    private static final String BEA_SUBJECT = "bea-openid";
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unconfiguredWeChatFailsClosedAndDoesNotProvision() {
        FailClosedEndUserWeChatAssertion assertion = new FailClosedEndUserWeChatAssertion();
        assertTrue(!assertion.configured());
        assertThrows(WeChatUnconfiguredException.class, () -> assertion.assertMiniProgram(JS_ADA));
        assertThrows(WeChatUnconfiguredException.class, () -> assertion.assertMiniProgram("anything"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void unconfiguredServiceDoesNotCreateAccountOrTenant(H2PlatformTables.Mode mode) {
        Harness h = Harness.open(mode, new FailClosedEndUserWeChatAssertion());
        int tenants = h.count("tenant");
        int accounts = h.countEuAccounts();
        assertThrows(
                WeChatUnconfiguredException.class,
                () -> h.weChatAuth.login(JS_ADA, TENANT_A, "JUnit", "127.0.0.1"));
        assertEquals(accounts, h.countEuAccounts());
        assertEquals(0, h.count("end_user_idp_link"));
        assertEquals(tenants, h.count("tenant"));
        h.assertOperatorTablesEmpty();
    }

    @Test
    void devAdapterPrefersUnionIdAndRejectsUnknownCodes() {
        DevEndUserWeChatAssertion dev = new DevEndUserWeChatAssertion();
        assertEquals(ADA_SUBJECT, dev.assertMiniProgram(JS_ADA).subject());
        assertEquals(WeChatProperties.ISSUER_MINI, dev.assertMiniProgram(JS_ADA).issuer());
        assertEquals(BEA_SUBJECT, dev.assertMiniProgram(JS_BEA).subject());
        dev.map("mapped-code", "mapped-open", "mapped-union");
        assertEquals("mapped-union", dev.assertMiniProgram("mapped-code").subject());
        assertThrows(InvalidEndUserWeChatException.class, () -> dev.assertMiniProgram("not-a-dev-code"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void firstLoginAutoProvisionsReuseBindUnbindAndRefuseInvalidTenants(H2PlatformTables.Mode mode)
            throws Exception {
        Harness h = Harness.open(mode);
        try {
            MvcResult first = h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"jsCode\":\"" + JS_ADA + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isString())
                    .andExpect(jsonPath("$.refreshToken").isString())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andReturn();
            JsonNode firstPair = JSON.readTree(first.getResponse().getContentAsString());
            String access = firstPair.get("accessToken").asText();
            assertTrue(access.startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX));
            assertTrue(firstPair.get("refreshToken").asText().startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX));
            EndUserPrincipal ada = h.tokens.resolveAccessToken(access).orElseThrow();
            String adaId = ada.accountId();
            assertEquals(
                    adaId,
                    h.accounts.findByIdp(WeChatProperties.ISSUER_MINI, ADA_SUBJECT)
                            .orElseThrow()
                            .account()
                            .accountId());
            assertEquals(1, h.count("end_user_idp_link"));
            h.assertOperatorTablesEmpty();

            MvcResult second = h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsCode\":\"" + JS_ADA + "\",\"tenantId\":\"" + TENANT_A + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String access2 = JSON.readTree(second.getResponse().getContentAsString())
                    .get("accessToken")
                    .asText();
            assertEquals(adaId, h.tokens.resolveAccessToken(access2).orElseThrow().accountId());
            assertEquals(1, h.count("end_user_idp_link"));
            assertEquals(1, h.countEuAccounts());

            int tenantsBefore = h.count("tenant");
            int linksBefore = h.count("end_user_idp_link");
            int accountsBefore = h.countEuAccounts();
            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsCode\":\"" + JS_BEA + "\",\"tenantId\":\"platform\"}"))
                    .andExpect(status().isBadRequest());
            assertThrows(
                    TenantNotFoundException.class,
                    () -> h.weChatAuth.login(JS_BEA, "missing-tenant", "JUnit", "127.0.0.1"));
            assertThrows(
                    TenantDisabledException.class,
                    () -> h.weChatAuth.login(JS_BEA, "frozen", "JUnit", "127.0.0.1"));
            assertThrows(
                    TenantMissingException.class, () -> h.weChatAuth.login(JS_BEA, "  ", "JUnit", "127.0.0.1"));
            assertEquals(accountsBefore, h.countEuAccounts());
            assertEquals(linksBefore, h.count("end_user_idp_link"));
            assertEquals(tenantsBefore, h.count("tenant"));
            assertTrue(h.accounts.findByIdp(WeChatProperties.ISSUER_MINI, BEA_SUBJECT).isEmpty());
            h.assertOperatorTablesEmpty();

            // Unlinked WeChat subject is auto-provisioned — not operator idp-unlinked deny.
            // 未绑定的微信主体会自动开户，不会走操作员「未绑定即拒绝」。
            MvcResult beaLogin = h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"jsCode\":\"" + JS_BEA + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String beaId = h.tokens
                    .resolveAccessToken(
                            JSON.readTree(beaLogin.getResponse().getContentAsString())
                                    .get("accessToken")
                                    .asText())
                    .orElseThrow()
                    .accountId();
            assertNotEquals(adaId, beaId);
            assertEquals(2, h.count("end_user_idp_link"));

            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/bind")
                            .with(asEndUser(h.principal(beaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsCode\":\"" + JS_ADA + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("idp-already-bound"));
            assertEquals(
                    adaId,
                    h.accounts.findByIdp(WeChatProperties.ISSUER_MINI, ADA_SUBJECT)
                            .orElseThrow()
                            .account()
                            .accountId());

            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/unbind")
                            .with(asOperator())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"issuer\":\"wechat:mini\",\"idpSubject\":\"" + ADA_SUBJECT + "\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));
            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/bind")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsCode\":\"" + JS_ADA + "\"}"))
                    .andExpect(status().isForbidden());

            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/unbind")
                            .with(asEndUser(h.principal(adaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"issuer\":\"wechat:mini\",\"idpSubject\":\"" + ADA_SUBJECT + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("last-authenticator"));
            assertEquals(
                    adaId,
                    h.accounts.findByIdp(WeChatProperties.ISSUER_MINI, ADA_SUBJECT)
                            .orElseThrow()
                            .account()
                            .accountId());

            h.accounts.bindPhone(adaId, "13800138000");
            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/unbind")
                            .with(asEndUser(h.principal(adaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsCode\":\"" + JS_ADA + "\"}"))
                    .andExpect(status().isNoContent());
            assertTrue(h.accounts.findByIdp(WeChatProperties.ISSUER_MINI, ADA_SUBJECT).isEmpty());

            // Released subject may bind to an account that does not already have an IdP link.
            // 释放后的主体可绑定到尚无 IdP 的账号（本波每账号一条 IdP）。
            EndUserRecord cara = h.accounts.provision(TENANT_A, "Cara");
            String caraId = cara.account().accountId();
            h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/bind")
                            .with(asEndUser(h.principal(caraId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"jsCode\":\"" + JS_ADA + "\"}"))
                    .andExpect(status().isNoContent());
            assertEquals(
                    caraId,
                    h.accounts.findByIdp(WeChatProperties.ISSUER_MINI, ADA_SUBJECT)
                            .orElseThrow()
                            .account()
                            .accountId());
            MvcResult asCara = h.mvc.perform(post(EndUserWeChatAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"jsCode\":\"" + JS_ADA + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            assertEquals(
                    caraId,
                    h.tokens.resolveAccessToken(
                                    JSON.readTree(asCara.getResponse().getContentAsString())
                                            .get("accessToken")
                                            .asText())
                            .orElseThrow()
                            .accountId());
            assertNotEquals(adaId, caraId);

            h.assertOperatorTablesEmpty();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static RequestPostProcessor asEndUser(EndUserPrincipal principal) {
        return request -> {
            SecurityContextHolder.getContext()
                    .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
            return request;
        };
    }

    private static RequestPostProcessor asOperator() {
        OperatorPrincipal operator = new OperatorPrincipal(
                "platform-operator", "hash", "id-1", "sub-1", Set.of("tenant.manage"), true);
        return request -> {
            SecurityContextHolder.getContext()
                    .setAuthentication(
                            new UsernamePasswordAuthenticationToken(operator, null, operator.getAuthorities()));
            return request;
        };
    }

    private static final class Harness {
        private final JdbcTemplate jdbc;
        private final JdbcEndUserAccountStore accounts;
        private final JdbcEndUserTokenStore tokens;
        private final EndUserWeChatAuthService weChatAuth;
        private final MockMvc mvc;

        private Harness(
                JdbcTemplate jdbc,
                JdbcEndUserAccountStore accounts,
                JdbcEndUserTokenStore tokens,
                EndUserWeChatAuthService weChatAuth,
                MockMvc mvc) {
            this.jdbc = jdbc;
            this.accounts = accounts;
            this.tokens = tokens;
            this.weChatAuth = weChatAuth;
            this.mvc = mvc;
        }

        static Harness open(H2PlatformTables.Mode mode) {
            return open(mode, new DevEndUserWeChatAssertion());
        }

        static Harness open(H2PlatformTables.Mode mode, EndUserWeChatAssertion weChat) {
            DataSource ds = H2PlatformTables.migrated(mode);
            JdbcTemplate jdbc = new JdbcTemplate(ds);
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                    TENANT_A,
                    "Tenant A");
            jdbc.update(
                    "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'SUSPENDED')",
                    "frozen",
                    "Frozen");
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
            Clock clock = Clock.systemUTC();
            JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
            JdbcEndUserTokenStore tokens = new JdbcEndUserTokenStore(
                    jdbc, tx, accounts, clock, Duration.ofMinutes(30), Duration.ofHours(8));
            JdbcEndUserLoginLockout lockout =
                    new JdbcEndUserLoginLockout(jdbc, tx, clock, 5, Duration.ofMinutes(15));
            EndUserWeChatAuthService weChatAuth =
                    new EndUserWeChatAuthService(accounts, tokens, weChat, lockout, jdbc);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new EndUserWeChatAuthEndpoint(weChatAuth))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter())
                    .build();
            return new Harness(jdbc, accounts, tokens, weChatAuth, mvc);
        }

        EndUserPrincipal principal(String accountId) {
            EndUserRecord record = accounts.findByAccountId(accountId).orElseThrow();
            return new EndUserPrincipal(
                    record.account().accountId(),
                    record.subject().subjectId(),
                    record.account().loginName(),
                    true);
        }

        int count(String table) {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
            return n == null ? 0 : n;
        }

        int countEuAccounts() {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE login_name LIKE 'eu:%'", Integer.class);
            return n == null ? 0 : n;
        }

        void assertOperatorTablesEmpty() {
            assertEquals(0, count("operator_access_token"));
            assertEquals(0, count("operator_refresh_token"));
            assertEquals(0, count("operator_idp_link"));
            assertEquals(0, count("operator_credential"));
            assertEquals(0, count("operator_login_lockout"));
        }
    }
}
