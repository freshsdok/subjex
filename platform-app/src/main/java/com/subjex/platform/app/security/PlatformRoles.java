package com.subjex.platform.app.security;

/**
 * PlatformRoles — 平台角色名常量：集中存放保留/常用角色名，避免字面量散落。
 * <p>
 * Names match {@code platform_role.role_name}. {@link #SUPER_ADMIN} is break-glass only:
 * it must not receive ordinary {@code role_permission} rows; privilege expansion happens at load time.
 * 名字与 {@code platform_role.role_name} 一致。{@link #SUPER_ADMIN} 仅作破窗：不得写入普通
 * {@code role_permission}；权限在加载时展开。
 */
public final class PlatformRoles {

    /** Ordinary full-catalog operator role — 普通全目录操作员角色。 */
    public static final String OPERATOR = "platform-operator";

    /** Ordinary read-only operator role — 普通只读操作员角色。 */
    public static final String READER = "platform-reader";

    /**
     * Isolated break-glass role. Keep {@code role_permission} empty; expand to the full
     * {@code platform_permission} catalog when the subject holds this role.
     * 隔离破窗角色。保持 {@code role_permission} 为空；主体持有该角色时展开为完整权限目录。
     */
    public static final String SUPER_ADMIN = "platform.super-admin";

    private PlatformRoles() {}
}
