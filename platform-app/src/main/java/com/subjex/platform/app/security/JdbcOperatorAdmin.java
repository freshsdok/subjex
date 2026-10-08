package com.subjex.platform.app.security;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorAdmin — 操作员管理：改密、禁用/启用、列表与创建、租户授权。
 * <p>
 * Password changes hash with the same encoder as bootstrap. Disable sets {@code account_state=DISABLED}.
 * Create refuses an existing login. Self-disable is refused to avoid locking the only console session.
 * 改密用与开通相同的编码器。禁用将账号标为 DISABLED。创建拒绝已存在登录名。禁止禁用自己以免锁死唯一会话。
 */
public final class JdbcOperatorAdmin {

    /** Account state written by disable — 禁用写入的账号状态。 */
    public static final String DISABLED = "DISABLED";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwordEncoder;
    private final OperatorTenantAccess tenantAccess;

    public JdbcOperatorAdmin(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            PasswordEncoder passwordEncoder,
            OperatorTenantAccess tenantAccess) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.passwordEncoder = Objects.requireNonNull(passwordEncoder, "passwordEncoder");
        this.tenantAccess = Objects.requireNonNull(tenantAccess, "tenantAccess");
    }

    public List<OperatorSummary> list() {
        return jdbc.query(
                """
                SELECT a.login_name, a.account_state, i.subject_id, i.identity_id,
                       (SELECT sr.role_name FROM subject_role sr WHERE sr.subject_id = i.subject_id LIMIT 1) AS role_name
                FROM account a
                JOIN operator_credential c ON c.account_id = a.account_id
                JOIN subject_identity i ON i.account_id = a.account_id AND i.tenant_id = ?
                ORDER BY a.login_name
                """,
                (row, n) -> new OperatorSummary(
                        row.getString("login_name"),
                        row.getString("account_state"),
                        row.getString("subject_id"),
                        row.getString("identity_id"),
                        row.getString("role_name")),
                JdbcOperatorDirectory.OPERATOR_TENANT);
    }

    public OperatorSummary create(String loginName, String password, String roleName) {
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("operator login is required");
        }
        if (password == null || password.length() < OperatorBootstrap.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "operator password must be at least " + OperatorBootstrap.MIN_PASSWORD_LENGTH + " characters");
        }
        String role = (roleName == null || roleName.isBlank()) ? OperatorBootstrap.DEFAULT_ROLE : roleName.trim();
        String login = loginName.trim();
        return transaction.execute(status -> {
            Integer roles = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM platform_role WHERE role_name = ?", Integer.class, role);
            if (roles == null || roles == 0) {
                throw new IllegalArgumentException("unknown operator role: " + role);
            }
            Integer existing = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM account WHERE login_name = ?", Integer.class, login);
            if (existing != null && existing > 0) {
                throw new IllegalArgumentException("operator login already exists: " + login);
            }
            String accountId = "account-" + UUID.randomUUID();
            String subjectId = "subject-" + UUID.randomUUID();
            String identityId = "identity-" + UUID.randomUUID();
            String hash = passwordEncoder.encode(password);
            jdbc.update(
                    "INSERT INTO account (account_id, login_name, account_state) VALUES (?, ?, 'ACTIVE')",
                    accountId,
                    login);
            jdbc.update(
                    "INSERT INTO subject (subject_id, subject_name, subject_kind) VALUES (?, ?, 'PERSON')",
                    subjectId,
                    "Operator " + login);
            jdbc.update(
                    """
                    INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                    VALUES (?, ?, ?, ?, 'ACTIVE')
                    """,
                    identityId,
                    accountId,
                    subjectId,
                    JdbcOperatorDirectory.OPERATOR_TENANT);
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES (?, ?)",
                    accountId,
                    hash);
            jdbc.update("INSERT INTO subject_role (subject_id, role_name) VALUES (?, ?)", subjectId, role);
            // New operators start with no tenant grants (fail-closed) — 新操作员默认无租户授权（失败关闭）。
            return new OperatorSummary(login, "ACTIVE", subjectId, identityId, role);
        });
    }

    public void changeOwnPassword(OperatorPrincipal self, String currentPassword, String newPassword) {
        Objects.requireNonNull(self, "self");
        if (currentPassword == null || currentPassword.isBlank()) {
            throw new IllegalArgumentException("current password is required");
        }
        requirePasswordLength(newPassword);
        AccountRow account = requireAccount(self.getUsername());
        if (!passwordEncoder.matches(currentPassword, account.passwordHash())) {
            throw new IllegalArgumentException("current password is wrong");
        }
        jdbc.update(
                "UPDATE operator_credential SET password_hash = ? WHERE account_id = ?",
                passwordEncoder.encode(newPassword),
                account.accountId());
    }

    public void changePassword(String loginName, String newPassword) {
        requirePasswordLength(newPassword);
        AccountRow account = requireAccount(loginName);
        jdbc.update(
                "UPDATE operator_credential SET password_hash = ? WHERE account_id = ?",
                passwordEncoder.encode(newPassword),
                account.accountId());
    }

    public void disable(OperatorPrincipal actor, String loginName) {
        Objects.requireNonNull(actor, "actor");
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("operator login is required");
        }
        if (actor.getUsername().equals(loginName.trim())) {
            throw new IllegalArgumentException("cannot disable your own account");
        }
        AccountRow account = requireAccount(loginName);
        jdbc.update(
                "UPDATE account SET account_state = ? WHERE account_id = ?", DISABLED, account.accountId());
    }

    public void enable(String loginName) {
        AccountRow account = requireAccount(loginName);
        jdbc.update(
                "UPDATE account SET account_state = 'ACTIVE' WHERE account_id = ?", account.accountId());
        jdbc.update(
                """
                UPDATE subject_identity SET identity_state = 'ACTIVE'
                WHERE account_id = ? AND tenant_id = ?
                """,
                account.accountId(),
                JdbcOperatorDirectory.OPERATOR_TENANT);
    }

    public List<String> listTenantGrants(String loginName) {
        AccountRow account = requireAccount(loginName);
        return tenantAccess.listGrants(account.subjectId());
    }

    public void replaceTenantGrants(String loginName, List<String> tenantIds) {
        AccountRow account = requireAccount(loginName);
        tenantAccess.replaceGrants(account.subjectId(), tenantIds == null ? List.of() : tenantIds);
    }

    /** Subject id for a login (token revocation) — 登录名对应主体（用于吊销令牌）。 */
    public String requireSubjectId(String loginName) {
        return requireAccount(loginName).subjectId();
    }

    private static void requirePasswordLength(String password) {
        if (password == null || password.length() < OperatorBootstrap.MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "operator password must be at least " + OperatorBootstrap.MIN_PASSWORD_LENGTH + " characters");
        }
    }

    private AccountRow requireAccount(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("operator login is required");
        }
        List<AccountRow> found = jdbc.query(
                """
                SELECT a.account_id, a.login_name, c.password_hash, i.subject_id
                FROM account a
                JOIN operator_credential c ON c.account_id = a.account_id
                JOIN subject_identity i ON i.account_id = a.account_id AND i.tenant_id = ?
                WHERE a.login_name = ?
                """,
                (row, n) -> new AccountRow(
                        row.getString("account_id"),
                        row.getString("login_name"),
                        row.getString("password_hash"),
                        row.getString("subject_id")),
                JdbcOperatorDirectory.OPERATOR_TENANT,
                loginName.trim());
        if (found.size() != 1) {
            throw new IllegalArgumentException("operator is unknown: " + loginName.trim());
        }
        return found.get(0);
    }

    /**
     * OperatorSummary — 操作员摘要：登录名、账号状态、主体、身份、角色。
     */
    public record OperatorSummary(
            String loginName, String accountState, String subjectId, String identityId, String roleName) {}

    private record AccountRow(String accountId, String loginName, String passwordHash, String subjectId) {}
}
