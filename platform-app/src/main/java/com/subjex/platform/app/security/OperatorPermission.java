package com.subjex.platform.app.security;

/**
 * OperatorPermission — 操作员权限：一个 HTTP 动作需要的那项具名权限。
 * <p>
 * The names match {@code platform_permission.permission_name}. A role grants them through {@code role_permission}.
 * Spring Security sees each name as one granted authority, without a {@code ROLE_} prefix.
 * 名字与 {@code platform_permission.permission_name} 一致。角色经 {@code role_permission} 授予它们。
 * Spring Security 把每个名字当成一项授权，不加 {@code ROLE_} 前缀。
 */
public enum OperatorPermission {

    /** Read admin JSON and audit entries — 读管理台 JSON 与审计。 */
    ADMIN_READ("admin.read"),
    /** Open the read-only operator pages — 打开只读操作页。 */
    PAGE_READ("page.read"),
    /** Read config entries — 读配置条目。 */
    CONFIG_READ("config.read"),
    /** Override one config key — 压过一个配置键。 */
    CONFIG_WRITE("config.write"),
    /** Read the registry — 读登记簿。 */
    REGISTRY_READ("registry.read"),
    /** Register an endpoint — 登记一个端点。 */
    REGISTRY_WRITE("registry.write"),
    /** Submit tasks — 提交任务。 */
    TASK_WRITE("task.write");

    private final String permissionName;

    OperatorPermission(String permissionName) {
        this.permissionName = permissionName;
    }

    /** Name stored in the table and granted as an authority — 表里存的名字，也是授权名。 */
    public String permissionName() {
        return permissionName;
    }
}
