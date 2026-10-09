package com.subjex.platform.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SuperAdminBootstrap — 破窗超管开通：幂等写入唯一一位 {@link PlatformRoles#SUPER_ADMIN} 操作员。
 * <p>
 * Dedicated break-glass path. Uses fixed ids (like {@link LocalOperatorSeeder}) so only one such
 * account exists. Assigns {@code subject_role} = {@link PlatformRoles#SUPER_ADMIN} and grants
 * {@link OperatorTenantAccess#ALL_TENANTS}. Does <strong>not</strong> insert {@code role_permission}
 * rows — privilege expansion is in {@link JdbcOperatorDirectory}. Enabled only via
 * {@link SuperAdminBootstrapConfiguration}. Ordinary {@link OperatorBootstrap} and
 * {@link JdbcOperatorAdmin#create} refuse this role.
 * 专用破窗路径。固定标识（同本地种子）保证只有一位。写入 {@code subject_role} 与租户通配 {@code *}，
 * <strong>不</strong>写 {@code role_permission}。仅由 {@link SuperAdminBootstrapConfiguration} 启用。
 */
public final class SuperAdminBootstrap {

    /** Fixed ids so the break-glass seed is idempotent — 固定标识，让破窗种子可重复执行。 */
    public static final String ACCOUNT_ID = "account-super-admin";
    public static final String SUBJECT_ID = "subject-super-admin";
    public static final String IDENTITY_ID = "identity-super-admin";

    private static final Logger LOG = LoggerFactory.getLogger(SuperAdminBootstrap.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminBootstrap(JdbcTemplate jdbc, TransactionTemplate transaction, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create or refresh the single break-glass super-admin — 创建或刷新唯一破窗超管。
     *
     * @param loginName unique login ({@code account.login_name})
     * @param password plain password (hashed before store; min {@link OperatorBootstrap#MIN_PASSWORD_LENGTH})
     */
    public void upsert(String loginName, String password) {
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("super-admin login is required");
        }
        if (password == null || password.length() < OperatorBootstrap.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "super-admin password must be at least " + OperatorBootstrap.MIN_PASSWORD_LENGTH + " characters");
        }
        String login = loginName.trim();
        String hash = passwordEncoder.encode(password);
        transaction.executeWithoutResult(status -> {
            Integer roles = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM platform_role WHERE role_name = ?",
                    Integer.class,
                    PlatformRoles.SUPER_ADMIN);
            if (roles == null || roles == 0) {
                throw new IllegalArgumentException("unknown operator role: " + PlatformRoles.SUPER_ADMIN);
            }
            // Refuse if another account already uses this login (not our fixed id).
            // 若其他账号已占用该登录名（非本固定 id）则拒绝。
            Integer foreignLogin = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE login_name = ? AND account_id <> ?",
                    Integer.class,
                    login,
                    ACCOUNT_ID);
            if (foreignLogin != null && foreignLogin > 0) {
                throw new IllegalArgumentException("operator login already exists: " + login);
            }
            upsert(
                    "SELECT COUNT(*) FROM account WHERE account_id = ?",
                    "UPDATE account SET login_name = ?, account_state = 'ACTIVE' WHERE account_id = ?",
                    "INSERT INTO account (login_name, account_state, account_id) VALUES (?, 'ACTIVE', ?)",
                    ACCOUNT_ID,
                    login);
            upsert(
                    "SELECT COUNT(*) FROM subject WHERE subject_id = ?",
                    "UPDATE subject SET subject_name = ?, subject_kind = 'PERSON' WHERE subject_id = ?",
                    "INSERT INTO subject (subject_name, subject_kind, subject_id) VALUES (?, 'PERSON', ?)",
                    SUBJECT_ID,
                    "Break-glass super-admin / 破窗超管");
            Integer identities = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject_identity WHERE identity_id = ?", Integer.class, IDENTITY_ID);
            if (identities == null || identities == 0) {
                jdbc.update(
                        """
                        INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                        VALUES (?, ?, ?, ?, 'ACTIVE')
                        """,
                        IDENTITY_ID,
                        ACCOUNT_ID,
                        SUBJECT_ID,
                        JdbcOperatorDirectory.OPERATOR_TENANT);
            } else {
                jdbc.update(
                        """
                        UPDATE subject_identity SET identity_state = 'ACTIVE', account_id = ?, subject_id = ?, tenant_id = ?
                        WHERE identity_id = ?
                        """,
                        ACCOUNT_ID,
                        SUBJECT_ID,
                        JdbcOperatorDirectory.OPERATOR_TENANT,
                        IDENTITY_ID);
            }
            upsert(
                    "SELECT COUNT(*) FROM operator_credential WHERE account_id = ?",
                    "UPDATE operator_credential SET password_hash = ? WHERE account_id = ?",
                    "INSERT INTO operator_credential (password_hash, account_id) VALUES (?, ?)",
                    ACCOUNT_ID,
                    hash);
            Integer subjectRoles = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject_role WHERE subject_id = ? AND role_name = ?",
                    Integer.class,
                    SUBJECT_ID,
                    PlatformRoles.SUPER_ADMIN);
            if (subjectRoles == null || subjectRoles == 0) {
                jdbc.update(
                        "INSERT INTO subject_role (subject_id, role_name) VALUES (?, ?)",
                        SUBJECT_ID,
                        PlatformRoles.SUPER_ADMIN);
            }
            Integer grants = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM operator_tenant_grant WHERE subject_id = ? AND tenant_id = ?",
                    Integer.class,
                    SUBJECT_ID,
                    OperatorTenantAccess.ALL_TENANTS);
            if (grants == null || grants == 0) {
                jdbc.update(
                        "INSERT INTO operator_tenant_grant (subject_id, tenant_id) VALUES (?, ?)",
                        SUBJECT_ID,
                        OperatorTenantAccess.ALL_TENANTS);
            }
        });
        LOG.warn(
                "BOOTSTRAP SUPER-ADMIN: upserted break-glass login '{}' with role {}. "
                        + "role_permission stays empty; privileges expand at load. "
                        + "Use only on a trusted network; disable platform.operator.super-admin-bootstrap after. "
                        + "破窗开通：已写入/更新超管；role_permission 保持空，权限在加载时展开；仅受信网络使用，用完关闭开关。",
                login,
                PlatformRoles.SUPER_ADMIN);
    }

    private void upsert(String countSql, String updateSql, String insertSql, String id, String value) {
        Integer count = jdbc.queryForObject(countSql, Integer.class, id);
        if (count != null && count > 0) {
            jdbc.update(updateSql, value, id);
        } else {
            jdbc.update(insertSql, value, id);
        }
    }
}
