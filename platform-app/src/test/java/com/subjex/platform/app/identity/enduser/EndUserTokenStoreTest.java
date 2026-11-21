package com.subjex.platform.app.identity.enduser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.tenant.TenantMissingException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * EndUserTokenStoreTest — purpose/gate WP2-2: prefixed issue, rotating refresh, logout revoke,
 * hashes at rest, operator token tables stay empty, refuse {@code platform} and missing tenants.
 * <p>
 * 目的/门禁 WP2-2：带前缀签发、轮换刷新、退出吊销、落库只存哈希、操作员令牌表保持为空、拒绝 {@code platform} 与缺失租户。
 */
class EndUserTokenStoreTest {

    private static final String TENANT_A = "tenant-a";
    private static final Duration ACCESS_TTL = Duration.ofMinutes(30);
    private static final Duration REFRESH_TTL = Duration.ofHours(8);
    private static final ObjectMapper JSON = new ObjectMapper();

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void issueRefreshLogoutAndRefuseInvalidTenants(H2PlatformTables.Mode mode) throws Exception {
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
        JdbcEndUserTokenStore tokens =
                new JdbcEndUserTokenStore(jdbc, tx, accounts, clock, ACCESS_TTL, REFRESH_TTL);
        EndUserRecord user = accounts.provision(TENANT_A, "Ada");
        String accountId = user.account().accountId();

        assertEquals(Duration.ofMinutes(30), tokens.accessTtl());
        assertEquals(Duration.ofHours(8), tokens.refreshTtl());

        JdbcEndUserTokenStore.IssuedTokens issued = tokens.issue(accountId, TENANT_A, "JUnit", "127.0.0.1");
        assertTrue(issued.accessToken().startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX));
        assertTrue(issued.refreshToken().startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX));
        assertEquals(ACCESS_TTL.toSeconds(), issued.expiresInSeconds());
        assertEquals(
                accountId,
                tokens.resolveAccessToken(issued.accessToken()).orElseThrow().accountId());
        assertStoredAsHashOnly(jdbc, "end_user_access_token", issued.accessToken());
        assertStoredAsHashOnly(jdbc, "end_user_refresh_token", issued.refreshToken());
        assertOperatorTokenTablesEmpty(jdbc);

        JdbcEndUserTokenStore.IssuedTokens rotated =
                tokens.refresh(issued.refreshToken(), "JUnit-refresh", "127.0.0.1");
        assertNotEquals(issued.accessToken(), rotated.accessToken());
        assertNotEquals(issued.refreshToken(), rotated.refreshToken());
        assertEquals(issued.familyId(), rotated.familyId());
        assertTrue(rotated.accessToken().startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX));
        assertTrue(rotated.refreshToken().startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX));
        assertTrue(tokens.resolveAccessToken(issued.accessToken()).isEmpty());
        assertEquals(
                accountId,
                tokens.resolveAccessToken(rotated.accessToken()).orElseThrow().accountId());
        assertThrows(
                InvalidEndUserTokenException.class,
                () -> tokens.refresh(issued.refreshToken(), "reuse", "127.0.0.1"));
        assertTrue(tokens.resolveAccessToken(rotated.accessToken()).isEmpty());
        assertThrows(
                InvalidEndUserTokenException.class,
                () -> tokens.refresh(rotated.refreshToken(), "after-reuse", "127.0.0.1"));
        assertOperatorTokenTablesEmpty(jdbc);

        JdbcEndUserTokenStore.IssuedTokens session = tokens.issue(accountId, TENANT_A, "JUnit", "127.0.0.1");
        tokens.revokeByRefreshToken(session.refreshToken());
        assertTrue(tokens.resolveAccessToken(session.accessToken()).isEmpty());
        assertThrows(
                InvalidEndUserTokenException.class,
                () -> tokens.refresh(session.refreshToken(), "after-logout", "127.0.0.1"));
        tokens.revokeByRefreshToken(session.refreshToken());
        tokens.revokeByRefreshToken("eu.rt.unknown-token");
        assertOperatorTokenTablesEmpty(jdbc);

        assertThrows(
                IllegalArgumentException.class,
                () -> tokens.issue(accountId, EndUserAccountStore.RESERVED_TENANT, "JUnit", "127.0.0.1"));
        assertThrows(TenantNotFoundException.class, () -> tokens.issue(accountId, "missing-tenant", "JUnit", "127.0.0.1"));
        assertThrows(TenantDisabledException.class, () -> tokens.issue(accountId, "frozen", "JUnit", "127.0.0.1"));
        assertThrows(TenantMissingException.class, () -> tokens.issue(accountId, "  ", "JUnit", "127.0.0.1"));
        assertEquals(0, count(jdbc, "operator_access_token"));
        assertEquals(0, count(jdbc, "operator_refresh_token"));

        MockMvc mvc = MockMvcBuilders.standaloneSetup(new EndUserAuthEndpoint(tokens))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
        JdbcEndUserTokenStore.IssuedTokens httpSession = tokens.issue(accountId, TENANT_A, "http", "127.0.0.1");
        MvcResult refreshed = mvc.perform(post(EndUserAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + httpSession.refreshToken() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        JsonNode body = JSON.readTree(refreshed.getResponse().getContentAsString());
        String newAccess = body.get("accessToken").asText();
        String newRefresh = body.get("refreshToken").asText();
        assertTrue(newAccess.startsWith(JdbcEndUserTokenStore.ACCESS_PREFIX));
        assertTrue(newRefresh.startsWith(JdbcEndUserTokenStore.REFRESH_PREFIX));
        mvc.perform(post(EndUserAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + httpSession.refreshToken() + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(EndUserAuthEndpoint.PATH + "/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isNoContent());
        assertTrue(tokens.resolveAccessToken(newAccess).isEmpty());
        mvc.perform(post(EndUserAuthEndpoint.PATH + "/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + newRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(EndUserAuthEndpoint.PATH + "/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"eu.rt.unknown\"}"))
                .andExpect(status().isNoContent());
        assertOperatorTokenTablesEmpty(jdbc);
    }

    @Test
    void prefixesAreDistinguishableFromOperatorTokens() {
        assertEquals("eu.at.", JdbcEndUserTokenStore.ACCESS_PREFIX);
        assertEquals("eu.rt.", JdbcEndUserTokenStore.REFRESH_PREFIX);
        assertFalse(JdbcEndUserTokenStore.ACCESS_PREFIX.startsWith("op."));
    }

    private static void assertStoredAsHashOnly(JdbcTemplate jdbc, String table, String rawToken) {
        String expected = JdbcEndUserTokenStore.sha256Hex(rawToken);
        List<String> hashes = jdbc.query("SELECT token_hash FROM " + table + " WHERE token_hash = ?",
                (row, n) -> row.getString("token_hash"),
                expected);
        assertEquals(1, hashes.size());
        assertEquals(expected, hashes.get(0));
        Integer rawHits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE token_id = ? OR family_id = ? OR token_hash = ?",
                Integer.class,
                rawToken,
                rawToken,
                rawToken);
        assertEquals(0, rawHits == null ? 0 : rawHits);
        Integer hashColPlain = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE token_hash = ?", Integer.class, rawToken);
        assertEquals(0, hashColPlain == null ? 0 : hashColPlain);
    }

    private static void assertOperatorTokenTablesEmpty(JdbcTemplate jdbc) {
        assertEquals(0, count(jdbc, "operator_access_token"));
        assertEquals(0, count(jdbc, "operator_refresh_token"));
        assertEquals(0, count(jdbc, "operator_credential"));
        assertEquals(0, count(jdbc, "operator_login_lockout"));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
