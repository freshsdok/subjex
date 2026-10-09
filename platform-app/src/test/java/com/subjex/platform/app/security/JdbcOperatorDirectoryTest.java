package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorDirectoryTest — purpose: operator directory load + local seeder.
 * Gates: seeded login resolves; unknown user fails closed (no principal).
 * <p>
 * 目的：操作员名录加载与本地种子。门禁：种子登录可解析；未知用户失败关闭。
 */
class JdbcOperatorDirectoryTest {

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private JdbcTemplate jdbc;
    private LocalOperatorSeeder seeder;
    private JdbcOperatorDirectory directory;

    @BeforeEach
    void migrate() {
        DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.MYSQL);
        jdbc = new JdbcTemplate(source);
        seeder = new LocalOperatorSeeder(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), encoder);
        directory = new JdbcOperatorDirectory(jdbc);
    }

    @Test
    void seededLocalOperatorHoldsEveryPermission() {
        seeder.seed("platform-operator", "change-me");
        OperatorPrincipal operator = (OperatorPrincipal) directory.loadUserByUsername("platform-operator");

        assertTrue(operator.isEnabled());
        assertEquals(LocalOperatorSeeder.IDENTITY_ID, operator.identityId());
        assertEquals(LocalOperatorSeeder.SUBJECT_ID, operator.subjectId());
        assertEquals(Set.of("admin.read", "page.read", "config.read", "config.write", "declaration.migrate",
                "declaration.promote", "declaration.read", "declaration.write", "operator.manage", "org.read",
                "org.write", "tenant.manage", "registry.read", "registry.write", "task.write"), operator.permissionNames());
        assertTrue(encoder.matches("change-me", operator.getPassword()));
        assertTrue(operator.getPassword().startsWith("{bcrypt}"));
    }

    @Test
    void readerRoleGetsOnlyReadPermissions() {
        OperatorDirectoryTestConfiguration.addViewer(jdbc, encoder);
        OperatorPrincipal viewer = (OperatorPrincipal) directory.loadUserByUsername("platform-viewer");
        assertEquals(Set.of("admin.read", "page.read", "config.read", "declaration.read", "org.read", "registry.read"),
                viewer.permissionNames());
    }

    @Test
    void seedingTwiceKeepsOneOperatorAndFollowsTheNewPassword() {
        seeder.seed("platform-operator", "change-me");
        seeder.seed("platform-operator", "second-pass");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM subject_identity", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM subject_role", Integer.class));
        OperatorPrincipal operator = (OperatorPrincipal) directory.loadUserByUsername("platform-operator");
        assertTrue(encoder.matches("second-pass", operator.getPassword()));
        assertFalse(encoder.matches("change-me", operator.getPassword()));
    }

    @Test
    void suspendedAccountOrRevokedIdentityIsDisabled() {
        seeder.seed("platform-operator", "change-me");
        jdbc.update("UPDATE account SET account_state = 'SUSPENDED'");
        assertFalse(directory.loadUserByUsername("platform-operator").isEnabled());

        jdbc.update("UPDATE account SET account_state = 'ACTIVE'");
        jdbc.update("UPDATE subject_identity SET identity_state = 'REVOKED'");
        assertFalse(directory.loadUserByUsername("platform-operator").isEnabled());
    }

    @Test
    void unknownOrNonOperatorLoginIsRefused() {
        assertThrows(UsernameNotFoundException.class, () -> directory.loadUserByUsername("nobody"));
        assertThrows(UsernameNotFoundException.class, () -> directory.loadUserByUsername(" "));
        // An account with a tenant identity but no platform identity is not an operator.
        // 只有业务租户身份、没有平台身份的账号不是操作员。
        jdbc.update("INSERT INTO account (account_id, login_name, account_state) VALUES ('a-1', 'tenant-user', 'ACTIVE')");
        jdbc.update("INSERT INTO operator_credential (account_id, password_hash) VALUES ('a-1', ?)", encoder.encode("x"));
        jdbc.update("""
                INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                VALUES ('i-1', 'a-1', 's-1', 'tenant-north', 'ACTIVE')
                """);
        assertThrows(UsernameNotFoundException.class, () -> directory.loadUserByUsername("tenant-user"));
    }

    @Test
    void seededLocalOperatorHasAllTenantsGrant() {
        seeder.seed("platform-operator", "change-me");
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM operator_tenant_grant WHERE subject_id = ? AND tenant_id = '*'",
                Integer.class,
                LocalOperatorSeeder.SUBJECT_ID));
    }
}
