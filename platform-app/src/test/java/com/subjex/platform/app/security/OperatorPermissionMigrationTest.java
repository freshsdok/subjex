package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * OperatorPermissionMigrationTest — purpose: Flyway permission catalog shape after migrate.
 * Gates: expected permission/role names present; no operator row from migration alone (dual H2 MODE).
 * <p>
 * 目的：迁移后权限目录形态。门禁：预期权限/角色名存在；仅迁移不产生操作员行。
 */
class OperatorPermissionMigrationTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void catalogHasRolesAndPermissionsButNoOperator(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));

        List<String> permissions = jdbc.queryForList(
                "SELECT permission_name FROM platform_permission ORDER BY permission_name", String.class);
        assertEquals(List.of("admin.read", "config.read", "config.write", "declaration.migrate", "declaration.promote",
                "declaration.read", "declaration.write", "operator.manage", "org.read", "org.write", "page.read", "registry.read",
                "registry.write", "task.write", "tenant.manage"), permissions);
        assertEquals(List.of("admin.read", "config.read", "declaration.read", "org.read", "page.read", "registry.read"),
                jdbc.queryForList(
                "SELECT permission_name FROM role_permission WHERE role_name = 'platform-reader' ORDER BY permission_name",
                String.class));
        assertEquals(15, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_name = 'platform-operator'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_name = 'platform.super-admin'", Integer.class));
        assertEquals(0, jdbc.queryForObject(
                "SELECT COUNT(*) FROM subject_role WHERE role_name = 'platform.super-admin'", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT tenant_state FROM tenant WHERE tenant_id = 'platform'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operator_credential", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subject_role", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operator_tenant_grant", Integer.class));
    }
}
