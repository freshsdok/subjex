package com.subjex.platform.app.identity.enduser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.subjex.platform.app.security.H2PlatformTables;
import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.identity.Identity;
import com.subjex.platform.contract.identity.IdentityState;
import com.subjex.platform.contract.identity.SubjectKind;
import com.subjex.platform.contract.tenant.TenantMissingException;
import com.subjex.platform.contract.tenant.TenantState;
import java.time.Clock;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * EndUserAccountStoreTest — purpose/gate WP2-1: provision/get, unique phone and IdP,
 * refuse reserved tenant {@code platform}, leave operator tables untouched.
 * <p>
 * 目的/门禁 WP2-1：开户与读取、手机号与 IdP 唯一、拒绝保留租户 {@code platform}、不写操作员表。
 */
class EndUserAccountStoreTest {

    private static final String TENANT_A = "tenant-a";
    private static final String TENANT_B = "tenant-b";

    @Test
    void memoryStoreProvisionsLooksUpAndEnforcesUniqueness() {
        MemoryEndUserAccountStore store = new MemoryEndUserAccountStore();
        store.registerTenant(TENANT_A, TenantState.ACTIVE);
        store.registerTenant(TENANT_B, TenantState.ACTIVE);
        store.registerTenant("frozen", TenantState.SUSPENDED);
        assertStoreRules(store);
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void jdbcStoreProvisionsLooksUpAndLeavesOperatorTablesUntouched(H2PlatformTables.Mode mode) {
        DataSource ds = H2PlatformTables.migrated(mode);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_A,
                "Tenant A");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'ACTIVE')",
                TENANT_B,
                "Tenant B");
        jdbc.update(
                "INSERT INTO tenant (tenant_id, tenant_name, tenant_state) VALUES (?, ?, 'SUSPENDED')",
                "frozen",
                "Frozen");
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        JdbcEndUserAccountStore store = new JdbcEndUserAccountStore(jdbc, tx, Clock.systemUTC());

        assertTablesExist(jdbc);
        assertEquals(0, count(jdbc, "end_user_access_token"));
        assertEquals(0, count(jdbc, "end_user_refresh_token"));
        assertEquals(0, count(jdbc, "end_user_login_lockout"));

        EndUserRecord created = assertStoreRules(store);

        assertEquals(0, count(jdbc, "operator_credential"));
        assertEquals(0, count(jdbc, "operator_idp_link"));
        assertEquals(0, count(jdbc, "operator_access_token"));
        assertEquals(0, count(jdbc, "operator_refresh_token"));
        assertEquals(0, count(jdbc, "operator_mfa_totp"));
        assertEquals(0, count(jdbc, "operator_tenant_grant"));
        assertEquals(0, count(jdbc, "operator_login_lockout"));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM subject_identity WHERE account_id = ? AND tenant_id = ?",
                        Integer.class,
                        created.account().accountId(),
                        EndUserAccountStore.RESERVED_TENANT));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM subject_role WHERE subject_id = ?",
                        Integer.class,
                        created.subject().subjectId()));
    }

    private static EndUserRecord assertStoreRules(EndUserAccountStore store) {
        EndUserRecord created = store.provision(TENANT_A, "Ada");
        assertEquals(SubjectKind.PERSON, created.subject().subjectKind());
        assertTrue(created.account().loginName().startsWith("eu:"));
        assertEquals(
                created.account().accountId(),
                store.findByAccountId(created.account().accountId()).orElseThrow().account().accountId());

        Identity home = store.findActiveIdentity(created.account().accountId(), TENANT_A).orElseThrow();
        assertEquals(TENANT_A, home.tenantId());
        assertEquals(IdentityState.ACTIVE, home.identityState());
        assertEquals(created.subject().subjectId(), home.subjectId());

        store.bindPhone(created.account().accountId(), "13800138000");
        assertEquals(
                created.account().accountId(),
                store.findByPhone("13800138000").orElseThrow().account().accountId());

        EndUserRecord other = store.provision(TENANT_A, "Bob");
        assertNotEquals(created.account().accountId(), other.account().accountId());
        IllegalArgumentException phoneClash = assertThrows(
                IllegalArgumentException.class,
                () -> store.bindPhone(other.account().accountId(), "13800138000"));
        assertTrue(phoneClash.getMessage().contains("phone already bound"));

        store.bindIdp(created.account().accountId(), "wechat", "wechat:mini", "openid-ada");
        assertEquals(
                created.account().accountId(),
                store.findByIdp("wechat:mini", "openid-ada").orElseThrow().account().accountId());
        IllegalArgumentException idpClash = assertThrows(
                IllegalArgumentException.class,
                () -> store.bindIdp(other.account().accountId(), "wechat", "wechat:mini", "openid-ada"));
        assertTrue(idpClash.getMessage().contains("idp subject already bound"));

        Identity second = store.addIdentity(created.account().accountId(), TENANT_B);
        assertEquals(TENANT_B, second.tenantId());
        assertTrue(store.findActiveIdentity(created.account().accountId(), TENANT_B).isPresent());

        assertThrows(TenantMissingException.class, () -> store.provision("  ", "Ada"));
        IllegalArgumentException reserved = assertThrows(
                IllegalArgumentException.class,
                () -> store.provision(EndUserAccountStore.RESERVED_TENANT, "Ada"));
        assertTrue(reserved.getMessage().contains("not an end-user home"));
        assertThrows(TenantNotFoundException.class, () -> store.provision("missing-tenant", "Ada"));
        assertThrows(TenantDisabledException.class, () -> store.provision("frozen", "Ada"));
        assertTrue(store.findActiveIdentity(created.account().accountId(), EndUserAccountStore.RESERVED_TENANT)
                .isEmpty());
        return created;
    }

    private static void assertTablesExist(JdbcTemplate jdbc) {
        assertEquals(0, count(jdbc, "end_user_phone_binding"));
        assertEquals(0, count(jdbc, "end_user_idp_link"));
        assertEquals(0, count(jdbc, "end_user_access_token"));
        assertEquals(0, count(jdbc, "end_user_refresh_token"));
        assertEquals(0, count(jdbc, "end_user_login_lockout"));
    }

    private static int count(JdbcTemplate jdbc, String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }
}
