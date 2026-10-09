package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SuperAdminBreakGlassTest — purpose/gate SA-1: break-glass expansion, refuse ordinary mint,
 * dedicated bootstrap only. Super-admin must not be minted via normal operator create.
 * <p>
 * 目的/门禁 SA-1：破窗权限展开；拒绝普通签发；仅专用开通。不得经普通创建操作员铸超管。
 */
class SuperAdminBreakGlassTest {

    private static final Set<String> FULL_CATALOG = Set.of(
            "admin.read",
            "page.read",
            "config.read",
            "config.write",
            "declaration.migrate",
            "declaration.promote",
            "declaration.read",
            "declaration.write",
            "operator.manage",
            "org.read",
            "org.write",
            "tenant.manage",
            "registry.read",
            "registry.write",
            "task.write");

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private JdbcTemplate jdbc;
    private TransactionTemplate transaction;
    private JdbcOperatorDirectory directory;
    private OperatorBootstrap bootstrap;
    private SuperAdminBootstrap superAdminBootstrap;
    private JdbcOperatorAdmin admin;

    @BeforeEach
    void migrate() {
        DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(source);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        directory = new JdbcOperatorDirectory(jdbc);
        bootstrap = new OperatorBootstrap(jdbc, transaction, encoder);
        superAdminBootstrap = new SuperAdminBootstrap(jdbc, transaction, encoder);
        admin = new JdbcOperatorAdmin(jdbc, transaction, encoder, new JdbcOperatorTenantAccess(jdbc, transaction));
    }

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void subjectWithOnlySuperAdminRoleGetsFullCatalogWithoutRolePermissionRows(H2PlatformTables.Mode mode) {
        DataSource source = H2PlatformTables.migrated(mode);
        JdbcTemplate dual = new JdbcTemplate(source);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        JdbcOperatorDirectory dualDirectory = new JdbcOperatorDirectory(dual);
        new SuperAdminBootstrap(dual, tx, encoder).upsert("break-glass", "long-enough");

        assertEquals(
                0,
                dual.queryForObject(
                        "SELECT COUNT(*) FROM role_permission WHERE role_name = ?",
                        Integer.class,
                        PlatformRoles.SUPER_ADMIN));
        OperatorPrincipal principal = (OperatorPrincipal) dualDirectory.loadUserByUsername("break-glass");
        assertEquals(FULL_CATALOG, principal.permissionNames());
        assertTrue(principal.permissionNames().contains("admin.read"));
        assertTrue(principal.permissionNames().contains("org.write"));
        assertTrue(principal.permissionNames().contains("operator.manage"));
    }

    @Test
    void ordinaryAdminCreateRefusesSuperAdminAndWritesNoAccount() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> admin.create("sneaky", "long-enough", PlatformRoles.SUPER_ADMIN));
        assertTrue(ex.getMessage().contains(PlatformRoles.SUPER_ADMIN));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subject_role", Integer.class));
    }

    @Test
    void ordinaryBootstrapRefusesSuperAdminAndWritesNoAccount() {
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class,
                () -> bootstrap.upsert("sneaky", "long-enough", PlatformRoles.SUPER_ADMIN));
        assertTrue(ex.getMessage().toLowerCase().contains("break-glass")
                || ex.getMessage().contains(PlatformRoles.SUPER_ADMIN));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class));
    }

    @Test
    void superAdminBootstrapUpsertsRoleGrantAndFullPermissions() {
        superAdminBootstrap.upsert("super-one", "long-enough");
        superAdminBootstrap.upsert("super-one", "second-password");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM subject_role WHERE subject_id = ? AND role_name = ?",
                        Integer.class,
                        SuperAdminBootstrap.SUBJECT_ID,
                        PlatformRoles.SUPER_ADMIN));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM role_permission WHERE role_name = ?",
                        Integer.class,
                        PlatformRoles.SUPER_ADMIN));
        assertEquals(
                1,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM operator_tenant_grant WHERE subject_id = ? AND tenant_id = ?",
                        Integer.class,
                        SuperAdminBootstrap.SUBJECT_ID,
                        OperatorTenantAccess.ALL_TENANTS));

        OperatorPrincipal principal = (OperatorPrincipal) directory.loadUserByUsername("super-one");
        assertEquals(FULL_CATALOG, principal.permissionNames());
        assertEquals(SuperAdminBootstrap.SUBJECT_ID, principal.subjectId());
        assertEquals(SuperAdminBootstrap.IDENTITY_ID, principal.identityId());
        assertTrue(encoder.matches("second-password", principal.getPassword()));
    }
}
