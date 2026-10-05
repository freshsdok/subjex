package com.subjex.platform.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * LocalOperatorSeeder — 本地操作员种子：只给一台开发机写入一位持有全部权限的操作员。
 * <p>
 * LOCAL ONLY. platform-app runs this only under the Spring profile {@code local}. It writes one account, one subject,
 * one identity in tenant {@code platform}, one password hash, and the role {@code platform-operator}.
 * The login and password come from {@code PLATFORM_OPERATOR_NAME} / {@code PLATFORM_OPERATOR_PASSWORD}
 * (default {@code platform-operator} / {@code change-me}). Running it again keeps one row each and refreshes the hash.
 * 只用于本地。platform-app 只在 Spring profile {@code local} 下执行它。它写入一个账号、一个主体、
 * 租户 {@code platform} 里的一个身份、一份口令摘要，以及角色 {@code platform-operator}。
 * 登录名和口令来自 {@code PLATFORM_OPERATOR_NAME} / {@code PLATFORM_OPERATOR_PASSWORD}（默认 {@code platform-operator} / {@code change-me}）。
 * 再次执行时每张表仍只有一行，并刷新口令摘要。
 */
public final class LocalOperatorSeeder {

    /** Fixed ids so the seed is idempotent — 固定标识，让种子可重复执行。 */
    public static final String ACCOUNT_ID = "account-local-operator";
    public static final String SUBJECT_ID = "subject-local-operator";
    public static final String IDENTITY_ID = "identity-local-operator";
    public static final String ROLE_NAME = "platform-operator";

    private static final Logger LOG = LoggerFactory.getLogger(LocalOperatorSeeder.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final PasswordEncoder passwordEncoder;

    public LocalOperatorSeeder(JdbcTemplate jdbc, TransactionTemplate transaction, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.passwordEncoder = passwordEncoder;
    }

    public void seed(String loginName, String password) {
        if (loginName == null || loginName.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException("local operator login and password are required");
        }
        String hash = passwordEncoder.encode(password);
        transaction.executeWithoutResult(status -> {
            upsert("SELECT COUNT(*) FROM account WHERE account_id = ?",
                    "UPDATE account SET login_name = ?, account_state = 'ACTIVE' WHERE account_id = ?",
                    "INSERT INTO account (login_name, account_state, account_id) VALUES (?, 'ACTIVE', ?)",
                    ACCOUNT_ID, loginName);
            upsert("SELECT COUNT(*) FROM subject WHERE subject_id = ?",
                    "UPDATE subject SET subject_name = ?, subject_kind = 'PERSON' WHERE subject_id = ?",
                    "INSERT INTO subject (subject_name, subject_kind, subject_id) VALUES (?, 'PERSON', ?)",
                    SUBJECT_ID, "Local operator / 本地操作员");
            Integer identities = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject_identity WHERE identity_id = ?", Integer.class, IDENTITY_ID);
            if (identities == null || identities == 0) {
                jdbc.update(
                        """
                        INSERT INTO subject_identity (identity_id, account_id, subject_id, tenant_id, identity_state)
                        VALUES (?, ?, ?, ?, 'ACTIVE')
                        """,
                        IDENTITY_ID, ACCOUNT_ID, SUBJECT_ID, JdbcOperatorDirectory.OPERATOR_TENANT);
            }
            upsert("SELECT COUNT(*) FROM operator_credential WHERE account_id = ?",
                    "UPDATE operator_credential SET password_hash = ? WHERE account_id = ?",
                    "INSERT INTO operator_credential (password_hash, account_id) VALUES (?, ?)",
                    ACCOUNT_ID, hash);
            Integer roles = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM subject_role WHERE subject_id = ? AND role_name = ?",
                    Integer.class, SUBJECT_ID, ROLE_NAME);
            if (roles == null || roles == 0) {
                jdbc.update("INSERT INTO subject_role (subject_id, role_name) VALUES (?, ?)", SUBJECT_ID, ROLE_NAME);
            }
        });
        LOG.warn("LOCAL ONLY: seeded operator '{}' with role {}. Do not run profile 'local' outside a laptop. "
                + "仅限本地：已写入操作员，请勿在开发机以外启用 local。", loginName, ROLE_NAME);
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
