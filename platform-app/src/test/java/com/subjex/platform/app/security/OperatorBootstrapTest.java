package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Operator bootstrap upsert — 操作员开通的幂等写入。
 */
class OperatorBootstrapTest {

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private JdbcTemplate jdbc;
    private OperatorBootstrap bootstrap;
    private JdbcOperatorDirectory directory;

    @BeforeEach
    void migrate() {
        DataSource source = H2PlatformTables.migrated(H2PlatformTables.Mode.POSTGRESQL);
        jdbc = new JdbcTemplate(source);
        bootstrap =
                new OperatorBootstrap(jdbc, new TransactionTemplate(new DataSourceTransactionManager(source)), encoder);
        directory = new JdbcOperatorDirectory(jdbc);
    }

    @Test
    void createsOperatorWithNamedRoleAndAllPermissionsForOperatorRole() {
        bootstrap.upsert("ops-one", "long-enough", "platform-operator");

        OperatorPrincipal operator = (OperatorPrincipal) directory.loadUserByUsername("ops-one");
        assertTrue(operator.isEnabled());
        assertEquals(
                Set.of(
                        "admin.read",
                        "page.read",
                        "config.read",
                        "config.write",
                        "operator.manage",
                        "registry.read",
                        "registry.write",
                        "task.write"),
                operator.permissionNames());
        assertTrue(encoder.matches("long-enough", operator.getPassword()));
        assertTrue(operator.getPassword().startsWith("{bcrypt}"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE login_name = 'ops-one'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM operator_tenant_grant g
                        JOIN subject_identity i ON i.subject_id = g.subject_id
                        JOIN account a ON a.account_id = i.account_id
                        WHERE a.login_name = 'ops-one' AND g.tenant_id = '*'
                        """,
                        Integer.class));
    }

    @Test
    void readerRoleGetsOnlyReadPermissions() {
        bootstrap.upsert("ops-reader", "long-enough", "platform-reader");

        OperatorPrincipal reader = (OperatorPrincipal) directory.loadUserByUsername("ops-reader");
        assertEquals(
                Set.of("admin.read", "page.read", "config.read", "registry.read"), reader.permissionNames());
    }

    @Test
    void upsertByLoginIsIdempotentAndRefreshesPasswordAndRole() {
        bootstrap.upsert("ops-one", "long-enough", "platform-reader");
        bootstrap.upsert("ops-one", "second-password", "platform-operator");

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM account WHERE login_name = 'ops-one'", Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM subject_identity i
                        JOIN account a ON a.account_id = i.account_id
                        WHERE a.login_name = 'ops-one' AND i.tenant_id = 'platform'
                        """,
                        Integer.class));
        assertEquals(
                1,
                jdbc.queryForObject(
                        """
                        SELECT COUNT(*) FROM subject_role sr
                        JOIN subject_identity i ON i.subject_id = sr.subject_id
                        JOIN account a ON a.account_id = i.account_id
                        WHERE a.login_name = 'ops-one'
                        """,
                        Integer.class));
        OperatorPrincipal operator = (OperatorPrincipal) directory.loadUserByUsername("ops-one");
        assertTrue(encoder.matches("second-password", operator.getPassword()));
        assertEquals(
                Set.of(
                        "admin.read",
                        "page.read",
                        "config.read",
                        "config.write",
                        "operator.manage",
                        "registry.read",
                        "registry.write",
                        "task.write"),
                operator.permissionNames());
    }

    @Test
    void refusesShortPasswordBlankLoginAndUnknownRole() {
        assertThrows(IllegalArgumentException.class, () -> bootstrap.upsert("ops", "short", "platform-operator"));
        assertThrows(IllegalArgumentException.class, () -> bootstrap.upsert(" ", "long-enough", "platform-operator"));
        assertThrows(IllegalArgumentException.class, () -> bootstrap.upsert("ops", "long-enough", "no-such-role"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account", Integer.class));
    }

    @Test
    void defaultRoleIsPlatformOperatorWhenRoleBlank() {
        bootstrap.upsert("ops-default", "long-enough", "  ");
        OperatorPrincipal operator = (OperatorPrincipal) directory.loadUserByUsername("ops-default");
        assertTrue(operator.permissionNames().contains("config.write"));
    }
}
