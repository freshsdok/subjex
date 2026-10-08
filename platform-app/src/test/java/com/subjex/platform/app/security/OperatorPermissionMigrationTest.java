package com.subjex.platform.app.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The permission catalog migrates in both compatibility modes — 权限目录在两种兼容模式下都能迁移。
 * <p>
 * H2 in PostgreSQL and MySQL mode runs V1 and V2 unchanged. The catalog holds roles and permissions but no person.
 * H2 的 PostgreSQL 与 MySQL 模式原样执行 V1 和 V2。目录里有角色和权限，但没有任何人。
 */
class OperatorPermissionMigrationTest {

    @ParameterizedTest
    @EnumSource(H2PlatformTables.Mode.class)
    void catalogHasRolesAndPermissionsButNoOperator(H2PlatformTables.Mode mode) {
        JdbcTemplate jdbc = new JdbcTemplate(H2PlatformTables.migrated(mode));

        List<String> permissions = jdbc.queryForList(
                "SELECT permission_name FROM platform_permission ORDER BY permission_name", String.class);
        assertEquals(List.of("admin.read", "config.read", "config.write", "operator.manage", "page.read",
                "registry.read", "registry.write", "task.write", "tenant.manage"), permissions);
        assertEquals(List.of("admin.read", "config.read", "page.read", "registry.read"), jdbc.queryForList(
                "SELECT permission_name FROM role_permission WHERE role_name = 'platform-reader' ORDER BY permission_name",
                String.class));
        assertEquals(9, jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permission WHERE role_name = 'platform-operator'", Integer.class));
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT tenant_state FROM tenant WHERE tenant_id = 'platform'", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operator_credential", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM subject_role", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM operator_tenant_grant", Integer.class));
    }
}
