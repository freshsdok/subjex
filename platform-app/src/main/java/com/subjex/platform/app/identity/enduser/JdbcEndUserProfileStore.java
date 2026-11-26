package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.jdbc.PlatformTables;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcEndUserProfileStore — JDBC {@code end_user_profile} beside the identity triad.
 * 终端资料旁表的 JDBC 实现。
 */
public final class JdbcEndUserProfileStore implements EndUserProfileStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;

    private static final RowMapper<EndUserProfile> ROW = (row, n) -> new EndUserProfile(
            row.getString("account_id"),
            row.getString("avatar_object_id"),
            row.getString("avatar_tenant_id"),
            row.getString("email"),
            instant(row, "email_verified_at"),
            row.getString("pending_email"),
            instant(row, "pending_email_requested_at"));

    public JdbcEndUserProfileStore(JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<EndUserProfile> findByAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return one(accountId.trim());
    }

    @Override
    public EndUserProfile setAvatar(String accountId, String objectId, String tenantId) {
        String aid = EndUserProfileStore.requireAccountId(accountId);
        String oid = trimToNull(objectId);
        String tid = trimToNull(tenantId);
        if ((oid == null) != (tid == null)) {
            throw new IllegalArgumentException("avatar object id and tenant id must be set or cleared together");
        }
        Instant now = clock.instant();
        return transaction.execute(status -> {
            ensureRow(aid, now);
            jdbc.update(
                    """
                    UPDATE end_user_profile
                       SET avatar_object_id = ?, avatar_tenant_id = ?, updated_at = ?
                     WHERE account_id = ?
                    """,
                    oid,
                    tid,
                    PlatformTables.timestamp(now),
                    aid);
            return one(aid).orElseThrow();
        });
    }

    @Override
    public EndUserProfile setPendingEmail(String accountId, String email, Instant requestedAt) {
        String aid = EndUserProfileStore.requireAccountId(accountId);
        String address = EndUserProfileStore.requireEmail(email);
        Instant at = Objects.requireNonNull(requestedAt, "requestedAt");
        Instant now = clock.instant();
        return transaction.execute(status -> {
            ensureRow(aid, now);
            jdbc.update(
                    """
                    UPDATE end_user_profile
                       SET pending_email = ?, pending_email_requested_at = ?, updated_at = ?
                     WHERE account_id = ?
                    """,
                    address,
                    PlatformTables.timestamp(at),
                    PlatformTables.timestamp(now),
                    aid);
            return one(aid).orElseThrow();
        });
    }

    @Override
    public EndUserProfile confirmPendingEmail(String accountId, String email, Instant verifiedAt) {
        String aid = EndUserProfileStore.requireAccountId(accountId);
        String address = EndUserProfileStore.requireEmail(email);
        Instant at = Objects.requireNonNull(verifiedAt, "verifiedAt");
        Instant now = clock.instant();
        return transaction.execute(status -> {
            int updated = jdbc.update(
                    """
                    UPDATE end_user_profile
                       SET email = ?, email_verified_at = ?,
                           pending_email = NULL, pending_email_requested_at = NULL,
                           updated_at = ?
                     WHERE account_id = ? AND pending_email = ?
                    """,
                    address,
                    PlatformTables.timestamp(at),
                    PlatformTables.timestamp(now),
                    aid,
                    address);
            if (updated != 1) {
                throw new IllegalArgumentException("no pending email to confirm");
            }
            return one(aid).orElseThrow();
        });
    }

    private void ensureRow(String accountId, Instant now) {
        if (one(accountId).isPresent()) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO end_user_profile (account_id, updated_at)
                VALUES (?, ?)
                """,
                accountId,
                PlatformTables.timestamp(now));
    }

    private Optional<EndUserProfile> one(String accountId) {
        List<EndUserProfile> rows = jdbc.query(
                """
                SELECT account_id, avatar_object_id, avatar_tenant_id, email, email_verified_at,
                       pending_email, pending_email_requested_at
                  FROM end_user_profile
                 WHERE account_id = ?
                """,
                ROW,
                accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        Timestamp timestamp = row.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
