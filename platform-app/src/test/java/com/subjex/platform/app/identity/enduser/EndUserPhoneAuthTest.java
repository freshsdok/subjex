package com.subjex.platform.app.identity.enduser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.OperatorPrincipal;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantEnforcementFilter;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
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
 * EndUserPhoneAuthTest — purpose/gate WP2-3: request/verify code, register/login in an ACTIVE
 * tenant, unique phone, lockout 5 / PT15M → 429, unbind+rebind, expire codes, refuse
 * {@code platform}/missing/disabled tenants, leave operator token tables empty.
 * <p>
 * 目的/门禁 WP2-3：验证码申请与校验、在 ACTIVE 租户注册登录、手机号唯一、5 次失败锁定返回 429、
 * 解绑再绑、过期验证码拒绝、拒绝 {@code platform}/缺失/停用租户、操作员令牌表保持为空。
 */
class EndUserPhoneAuthTest {

    private static final String TENANT_A = "tenant-a";
    private static final String PHONE_A = "13800138000";
    private static final String PHONE_B = "13900139000";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CODE_TTL = Duration.ofMinutes(5);
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofHours(8);

    @Test
    void devAdapterIssuesVerifiesAndRejectsExpiredCodes() {
        AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-17T00:00:00Z"));
        MemoryVerificationCodePort port = new MemoryVerificationCodePort(
                clock, CODE_TTL, true, new DevVerificationCodeSender());
        VerificationCodePort.IssuedCode issued =
                port.issue(PHONE_A, VerificationCodePort.PURPOSE_LOGIN);
        assertEquals(CODE_TTL.toSeconds(), issued.expiresInSeconds());
        assertNotNull(issued.devCode());
        assertEquals(6, issued.devCode().length());
        assertEquals(issued.devCode(), port.peekForDev(PHONE_A, VerificationCodePort.PURPOSE_LOGIN).orElseThrow());
        assertTrue(port.verifyAndConsume(PHONE_A, VerificationCodePort.PURPOSE_LOGIN, issued.devCode()));
        assertFalse(port.verifyAndConsume(PHONE_A, VerificationCodePort.PURPOSE_LOGIN, issued.devCode()));

        VerificationCodePort.IssuedCode second =
                port.issue(PHONE_A, VerificationCodePort.PURPOSE_LOGIN);
        clock.advance(CODE_TTL);
        assertFalse(port.verifyAndConsume(PHONE_A, VerificationCodePort.PURPOSE_LOGIN, second.devCode()));
        assertTrue(port.peekForDev(PHONE_A, VerificationCodePort.PURPOSE_LOGIN).isEmpty());
    }

    @Test
    void productionSenderFailsClosedAndDoesNotRevealOrKeepACode() {
        MemoryVerificationCodePort port = new MemoryVerificationCodePort(
                Clock.systemUTC(), CODE_TTL, false, new FailClosedVerificationCodeSender());
        assertThrows(
                VerificationCodeSenderUnconfiguredException.class,
                () -> port.issue(PHONE_A, VerificationCodePort.PURPOSE_LOGIN));
        assertTrue(port.peekForDev(PHONE_A, VerificationCodePort.PURPOSE_LOGIN).isEmpty());
        assertFalse(port.verifyAndConsume(PHONE_A, VerificationCodePort.PURPOSE_LOGIN, "123456"));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void registerLoginLockoutUnbindAndRefuseInvalidTenants(H2PlatformTables.Mode mode) throws Exception {
        Harness h = Harness.open(mode);
        try {
            MvcResult requested = h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/request-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"purpose\":\"phone-login\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.devCode").isString())
                    .andExpect(jsonPath("$.purpose").value("phone-login"))
                    .andReturn();
            String code = JSON.readTree(requested.getResponse().getContentAsString()).get("devCode").asText();

            MvcResult registered = h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"" + code + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.accessToken").isString())
                    .andExpect(jsonPath("$.refreshToken").isString())
                    .andExpect(jsonPath("$.tokenType").value("Bearer"))
                    .andReturn();
            JsonNode firstPair = JSON.readTree(registered.getResponse().getContentAsString());
            String access = firstPair.get("accessToken").asText();
            assertTrue(access.startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX));
            assertTrue(firstPair.get("refreshToken").asText().startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX));
            EndUserPrincipal ada = h.tokens.resolveAccessToken(access).orElseThrow();
            String adaId = ada.accountId();
            assertEquals(1, h.count("end_user_phone_binding"));
            h.assertOperatorTokenTablesEmpty();

            String loginCode = h.requestLoginCode(PHONE_A);
            MvcResult loggedIn = h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"" + loginCode
                                    + "\",\"tenantId\":\"" + TENANT_A + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            String access2 = JSON.readTree(loggedIn.getResponse().getContentAsString())
                    .get("accessToken")
                    .asText();
            assertEquals(adaId, h.tokens.resolveAccessToken(access2).orElseThrow().accountId());
            assertEquals(1, h.count("end_user_phone_binding"));

            int tenantsBefore = h.count("tenant");
            int accountsBefore = h.count("end_user_phone_binding");
            String reservedCode = h.requestLoginCode(PHONE_B);
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_B + "\",\"code\":\"" + reservedCode
                                    + "\",\"tenantId\":\"platform\"}"))
                    .andExpect(status().isBadRequest());
            assertThrows(
                    TenantNotFoundException.class,
                    () -> h.phoneAuth.loginOrRegister(
                            PHONE_B, h.requestLoginCode(PHONE_B), "missing-tenant", "JUnit", "127.0.0.1"));
            assertThrows(
                    TenantDisabledException.class,
                    () -> h.phoneAuth.loginOrRegister(
                            PHONE_B, h.requestLoginCode(PHONE_B), "frozen", "JUnit", "127.0.0.1"));
            assertThrows(
                    TenantMissingException.class,
                    () -> h.phoneAuth.loginOrRegister(PHONE_B, "000000", "  ", "JUnit", "127.0.0.1"));
            assertEquals(accountsBefore, h.count("end_user_phone_binding"));
            assertEquals(tenantsBefore, h.count("tenant"));
            assertTrue(h.accounts.findByPhone(PHONE_B).isEmpty());

            h.phoneAuth.loginOrRegister(
                    PHONE_B, h.requestLoginCode(PHONE_B), TENANT_A, "JUnit", "127.0.0.1");
            String bobId = h.accounts.findByPhone(PHONE_B).orElseThrow().account().accountId();
            assertNotEquals(adaId, bobId);
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/bind")
                            .with(asEndUser(h.principal(bobId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\""
                                    + h.requestBindCode(PHONE_A) + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("phone-already-bound"));
            assertEquals(adaId, h.accounts.findByPhone(PHONE_A).orElseThrow().account().accountId());

            for (int i = 0; i < 5; i++) {
                h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                                .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"000000\"}"))
                        .andExpect(status().isUnauthorized());
            }
            String stillValid = h.requestLoginCode(PHONE_A);
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/request-code")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.reason").value("login-lockout"));
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"" + stillValid + "\"}"))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.reason").value("login-lockout"));
            assertEquals(0, h.count("operator_login_lockout"));

            // Explicit unlock, then a fresh code logs in — 显式解锁后用新验证码登录成功。
            h.lockout.clear(PHONE_A);
            String afterUnlock = h.requestLoginCode(PHONE_A);
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"" + afterUnlock + "\"}"))
                    .andExpect(status().isOk());

            // Window expiry also lifts the lock when the code TTL is long enough.
            // 锁定窗口过期后，只要验证码仍有效即可登录。
            Harness longCodes = Harness.open(mode, Duration.ofHours(1), Duration.ofMinutes(15));
            try {
                longCodes.phoneAuth.loginOrRegister(
                        PHONE_A, longCodes.requestLoginCode(PHONE_A), TENANT_A, "JUnit", "127.0.0.1");
                for (int i = 0; i < 5; i++) {
                    assertThrows(
                            InvalidVerificationCodeException.class,
                            () -> longCodes.phoneAuth.loginOrRegister(
                                    PHONE_A, "000000", TENANT_A, "JUnit", "127.0.0.1"));
                }
                assertThrows(
                        EndUserLoginLockoutException.class,
                        () -> longCodes.phoneAuth.loginOrRegister(
                                PHONE_A, "000000", TENANT_A, "JUnit", "127.0.0.1"));
                String waiting = longCodes.requestLoginCode(PHONE_A);
                longCodes.clock.advance(Duration.ofMinutes(15));
                JdbcEndUserTokenStore.IssuedTokens afterWindow =
                        longCodes.phoneAuth.loginOrRegister(PHONE_A, waiting, TENANT_A, "JUnit", "127.0.0.1");
                assertTrue(afterWindow.accessToken().startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX));
                longCodes.assertOperatorTokenTablesEmpty();
            } finally {
                SecurityContextHolder.clearContext();
            }

            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/unbind")
                            .with(asOperator())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"000000\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.reason").value("end-user-authentication-required"));
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/bind")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"000000\"}"))
                    .andExpect(status().isForbidden());

            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/unbind")
                            .with(asEndUser(h.principal(adaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\""
                                    + h.requestUnbindCode(PHONE_A) + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.reason").value("last-authenticator"));
            assertEquals(adaId, h.accounts.findByPhone(PHONE_A).orElseThrow().account().accountId());
            // Keep a WeChat link so phone unbind is not the last authenticator (WP2-4).
            // 先留一条微信绑定，手机号解绑才不是最后认证器。
            h.accounts.bindIdp(adaId, "wechat", "wechat:mini", "openid-ada-keep");

            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/unbind")
                            .with(asEndUser(h.principal(adaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\""
                                    + h.requestUnbindCode(PHONE_A) + "\"}"))
                    .andExpect(status().isNoContent());
            assertTrue(h.accounts.findByPhone(PHONE_A).isEmpty());

            // Same account may rebind the released number — 原账号可重新绑定已释放号码。
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/bind")
                            .with(asEndUser(h.principal(adaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\""
                                    + h.requestBindCode(PHONE_A) + "\"}"))
                    .andExpect(status().isNoContent());
            assertEquals(adaId, h.accounts.findByPhone(PHONE_A).orElseThrow().account().accountId());

            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/unbind")
                            .with(asEndUser(h.principal(adaId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\""
                                    + h.requestUnbindCode(PHONE_A) + "\"}"))
                    .andExpect(status().isNoContent());
            // Another account without a phone may bind the released number (one phone per account).
            // 尚无手机号的另一账号可绑定已释放号码（每账号仍限一个手机号）。
            EndUserRecord cara = h.accounts.provision(TENANT_A, "Cara");
            String caraId = cara.account().accountId();
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/bind")
                            .with(asEndUser(h.principal(caraId)))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\""
                                    + h.requestBindCode(PHONE_A) + "\"}"))
                    .andExpect(status().isNoContent());
            assertEquals(caraId, h.accounts.findByPhone(PHONE_A).orElseThrow().account().accountId());
            String releasedLogin = h.requestLoginCode(PHONE_A);
            MvcResult asCara = h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"phone\":\"" + PHONE_A + "\",\"code\":\"" + releasedLogin + "\"}"))
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

            // Expired codes are rejected on the login path too — 登录路径同样拒绝过期验证码。
            String late = h.requestLoginCode(PHONE_B);
            h.clock.advance(CODE_TTL);
            h.mvc.perform(post(EndUserPhoneAuthEndpoint.PATH + "/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header(TenantEnforcementFilter.TENANT_HEADER, TENANT_A)
                            .content("{\"phone\":\"" + PHONE_B + "\",\"code\":\"" + late + "\"}"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.reason").value("invalid-verification-code"));

            h.assertOperatorTokenTablesEmpty();
            assertEquals(0, h.count("operator_login_lockout"));
            assertNull(JSON.readTree("{\"devCode\":null}").get("missing"));
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
        private final AdjustableClock clock;
        private final JdbcTemplate jdbc;
        private final JdbcEndUserAccountStore accounts;
        private final JdbcEndUserTokenStore tokens;
        private final MemoryVerificationCodePort codes;
        private final JdbcEndUserLoginLockout lockout;
        private final EndUserPhoneAuthService phoneAuth;
        private final MockMvc mvc;

        private Harness(
                AdjustableClock clock,
                JdbcTemplate jdbc,
                JdbcEndUserAccountStore accounts,
                JdbcEndUserTokenStore tokens,
                MemoryVerificationCodePort codes,
                JdbcEndUserLoginLockout lockout,
                EndUserPhoneAuthService phoneAuth,
                MockMvc mvc) {
            this.clock = clock;
            this.jdbc = jdbc;
            this.accounts = accounts;
            this.tokens = tokens;
            this.codes = codes;
            this.lockout = lockout;
            this.phoneAuth = phoneAuth;
            this.mvc = mvc;
        }

        static Harness open(H2PlatformTables.Mode mode) {
            return open(mode, CODE_TTL, Duration.ofMinutes(15));
        }

        static Harness open(H2PlatformTables.Mode mode, Duration codeTtl, Duration lockoutDuration) {
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
            AdjustableClock clock = new AdjustableClock(Instant.parse("2026-11-17T00:00:00Z"));
            JdbcEndUserAccountStore accounts = new JdbcEndUserAccountStore(jdbc, tx, clock);
            JdbcEndUserTokenStore tokens =
                    new JdbcEndUserTokenStore(jdbc, tx, accounts, clock, ACCESS_TTL, REFRESH_TTL);
            MemoryVerificationCodePort codes =
                    new MemoryVerificationCodePort(clock, codeTtl, true, new DevVerificationCodeSender());
            JdbcEndUserLoginLockout lockout = new JdbcEndUserLoginLockout(jdbc, tx, clock, 5, lockoutDuration);
            EndUserPhoneAuthService phoneAuth =
                    new EndUserPhoneAuthService(accounts, tokens, codes, lockout, jdbc);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new EndUserPhoneAuthEndpoint(phoneAuth))
                    .setMessageConverters(new JacksonJsonHttpMessageConverter())
                    .build();
            return new Harness(clock, jdbc, accounts, tokens, codes, lockout, phoneAuth, mvc);
        }

        String requestLoginCode(String phone) {
            return codes.issue(phone, VerificationCodePort.PURPOSE_LOGIN).devCode();
        }

        String requestBindCode(String phone) {
            return codes.issue(phone, VerificationCodePort.PURPOSE_BIND).devCode();
        }

        String requestUnbindCode(String phone) {
            return codes.issue(phone, VerificationCodePort.PURPOSE_UNBIND).devCode();
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

        void assertOperatorTokenTablesEmpty() {
            assertEquals(0, count("operator_access_token"));
            assertEquals(0, count("operator_refresh_token"));
            assertEquals(0, count("operator_credential"));
            assertEquals(0, count("operator_login_lockout"));
        }
    }

    private static final class AdjustableClock extends Clock {
        private final AtomicReference<Instant> now;

        private AdjustableClock(Instant now) {
            this.now = new AtomicReference<>(now);
        }

        void advance(Duration by) {
            now.updateAndGet(t -> t.plus(by));
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    }
}
