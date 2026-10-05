package com.subjex.platform.app.security;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OperatorBootstrap — 操作员开通：按登录名幂等写入/更新一位操作员（与 LocalOperatorSeeder 同一批表）。
 * <p>
 * Upserts by {@code account.login_name}: account, subject, platform identity, password hash, and one role.
 * Does not run by itself — {@link OperatorBootstrapConfiguration} enables it only when
 * {@code platform.operator.bootstrap=true}. Password shorter than {@link #MIN_PASSWORD_LENGTH} is refused.
 * 按登录名幂等写入：账号、主体、平台身份、口令摘要、一个角色。本身不自动跑；只有
 * {@code platform.operator.bootstrap=true} 时由 {@link OperatorBootstrapConfiguration} 启用。
 * 口令短于 {@link #MIN_PASSWORD_LENGTH} 会拒绝。
 */
public final class OperatorBootstrap {

    /** Shortest password this bootstrap accepts — 本开通路径接受的最短口令。 */
    public static final int MIN_PASSWORD_LENGTH = 8;

    /** Default role when none is given — 未指定时的默认角色。 */
    public static final String DEFAULT_ROLE = "platform-operator";

    private static final Logger LOG = LoggerFactory.getLogger(OperatorBootstrap.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwordEncoder;

    public OperatorBootstrap(JdbcTemplate jdbc, TransactionTemplate transaction, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Create or refresh one operator — 创建或刷新一位操作员。
     *
     * @param loginName unique login ({@code account.login_name})
     * @param password plain password (hashed before store)
     * @param roleName existing {@code platform_role.role_name}
     */
    public void upsert(String loginName, String password, String roleName) {
        if (loginName == null || loginName.isBlank()) {
            throw new IllegalArgumentException("operator login is required");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "operator password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        String role = (roleName == null || roleName.isBlank()) ? DEFAULT_ROLE : roleName.trim();
        String login = loginName.trim();
        String hash = passwordEncoder.encode(password);
        transaction.executeWithoutResult(status -> {
            Integer roles = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM platform_role WHERE role_name = ?", Integer.class, role);
            if (roles == null || roles == 0) {
                throw new IllegalArgumentException("unknown operator role: " + role);
            }
            List<String> existing = jdbc.query(
                    "SELECT account_id FROM account WHERE login_name = ?",
                    (row, n) -> row.getString("account_id"),
                    login);
            if (existing.isEmpty()) {
                insertNew(login, hash, role);
            } else {
                refreshExisting(existing.get(0), login, hash, role);
            }
        });
        LOG.warn(
                "BOOTSTRAP: upserted operator '{}' with role {}. Use only on a trusted network; disable the flag after. "
                        + "开通：已写入/更新操作员；仅在受信网络使用，用完关闭该开关。",
                login,
                role);
    }

    private void insertNew(String login, String hash, String role) {
        String accountId = "account-" + UUID.randomUUID();
        String subjectId = "subject-" + UUID.randomUUID();
        String identityId = "identity-" + UUID.randomUUID();
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
    }

    private void refreshExisting(String accountId, String login, String hash, String role) {
        jdbc.update(
                "UPDATE account SET login_name = ?, account_state = 'ACTIVE' WHERE account_id = ?",
                login,
                accountId);
        Integer credentials = jdbc.queryForObject(
                "SELECT COUNT(*) FROM operator_credential WHERE account_id = ?", Integer.class, accountId);
        if (credentials == null || credentials == 0) {
            jdbc.update(
                    "INSERT INTO operator_credential (account_id, password_hash) VALUES (?, ?)",
                    accountId,
                    hash);
        } else {
            jdbc.update(
                    "UPDATE operator_credential SET password_hash = ? WHERE account_id = ?",
                    hash,
                    accountId);
        }
        String subjectId = ensurePlatformIdentity(accountId, login);
        jdbc.update("DELETE FROM subject_role WHERE subject_id = ?", subjectId);
        jdbc.update("INSERT INTO subject_role (subject_id, role_name) VALUES (?, ?)", subjectId, role);
    }

    private String ensurePlatformIdentity(String accountId, String login) {
        List<String> subjects = jdbc.query(
                """
                SELECT subject_id FROM subject_identity
                WHERE account_id = ? AND tenant_id = ?
                """,
                (row, n) -> row.getString("subject_id"),
                accountId,
                JdbcOperatorDirectory.OPERATOR_TENANT);
        if (!subjects.isEmpty()) {
            String subjectId = subjects.get(0);
            jdbc.update(
                    """
                    UPDATE subject_identity SET identity_state = 'ACTIVE'
                    WHERE account_id = ? AND tenant_id = ?
                    """,
                    accountId,
                    JdbcOperatorDirectory.OPERATOR_TENANT);
            return subjectId;
        }
        String subjectId = "subject-" + UUID.randomUUID();
        String identityId = "identity-" + UUID.randomUUID();
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
        return subjectId;
    }
}
