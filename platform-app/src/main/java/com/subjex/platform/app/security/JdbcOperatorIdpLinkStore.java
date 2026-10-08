package com.subjex.platform.app.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorIdpLinkStore — IdP {@code sub}（+ issuer）到操作员账号的绑定表。
 * <p>
 * Unlinked IdP identities never create accounts here. Admin bind/unlink only.
 * 未绑定的 IdP 身份绝不在此建号；仅管理员绑定/解绑。
 */
public final class JdbcOperatorIdpLinkStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public JdbcOperatorIdpLinkStore(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<LinkedOperator> findByIssuerAndSubject(String issuer, String idpSubject) {
        if (issuer == null || issuer.isBlank() || idpSubject == null || idpSubject.isBlank()) {
            return Optional.empty();
        }
        List<LinkedOperator> rows = jdbc.query(
                """
                SELECT l.link_id, l.issuer, l.idp_subject, l.account_id, l.subject_id, a.login_name
                FROM operator_idp_link l
                JOIN account a ON a.account_id = l.account_id
                WHERE l.issuer = ? AND l.idp_subject = ?
                """,
                (row, n) -> new LinkedOperator(
                        row.getString("link_id"),
                        row.getString("issuer"),
                        row.getString("idp_subject"),
                        row.getString("account_id"),
                        row.getString("subject_id"),
                        row.getString("login_name")),
                issuer.trim(),
                idpSubject.trim());
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    public Optional<LinkedOperator> findByLoginName(String loginName) {
        if (loginName == null || loginName.isBlank()) {
            return Optional.empty();
        }
        List<LinkedOperator> rows = jdbc.query(
                """
                SELECT l.link_id, l.issuer, l.idp_subject, l.account_id, l.subject_id, a.login_name
                FROM operator_idp_link l
                JOIN account a ON a.account_id = l.account_id
                WHERE a.login_name = ?
                """,
                (row, n) -> new LinkedOperator(
                        row.getString("link_id"),
                        row.getString("issuer"),
                        row.getString("idp_subject"),
                        row.getString("account_id"),
                        row.getString("subject_id"),
                        row.getString("login_name")),
                loginName.trim());
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    public LinkedOperator bind(String accountId, String subjectId, String loginName, String issuer, String idpSubject) {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(subjectId, "subjectId");
        String iss = requireNonBlank(issuer, "issuer");
        String sub = requireNonBlank(idpSubject, "idpSubject");
        return transaction.execute(status -> {
            if (findByIssuerAndSubject(iss, sub).isPresent()) {
                throw new IllegalArgumentException("idp-subject-already-linked");
            }
            findByLoginName(loginName).ifPresent(existing -> {
                throw new IllegalArgumentException("operator-already-linked");
            });
            String linkId = "idp-link-" + UUID.randomUUID();
            Instant now = clock.instant();
            jdbc.update(
                    """
                    INSERT INTO operator_idp_link (link_id, issuer, idp_subject, account_id, subject_id, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    linkId,
                    iss,
                    sub,
                    accountId,
                    subjectId,
                    java.sql.Timestamp.from(now));
            return new LinkedOperator(linkId, iss, sub, accountId, subjectId, loginName);
        });
    }

    public void unlinkByLoginName(String loginName) {
        String login = requireNonBlank(loginName, "loginName");
        transaction.executeWithoutResult(status -> {
            int removed = jdbc.update(
                    """
                    DELETE FROM operator_idp_link
                    WHERE account_id = (SELECT account_id FROM account WHERE login_name = ?)
                    """,
                    login);
            if (removed == 0) {
                throw new IllegalArgumentException("idp-link-missing");
            }
        });
    }

    private static String requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + "-required");
        }
        return value.trim();
    }

    public record LinkedOperator(
            String linkId,
            String issuer,
            String idpSubject,
            String accountId,
            String subjectId,
            String loginName) {}
}
