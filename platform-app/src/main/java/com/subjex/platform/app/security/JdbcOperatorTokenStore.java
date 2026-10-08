package com.subjex.platform.app.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorTokenStore — 不透明访问令牌与轮换刷新令牌：摘要入库、按摘要查找、家族吊销。
 * <p>
 * Access tokens are short-lived; refresh tokens rotate on each use. Presenting a already-revoked refresh
 * token revokes the whole family (reuse detection). Raw token bytes are never written to the database.
 * 访问令牌短效；刷新令牌每次使用轮换。出示已吊销的刷新令牌会吊销整族（重放检测）。原始令牌从不入库存。
 */
public final class JdbcOperatorTokenStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcOperatorDirectory directory;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JdbcOperatorTokenStore(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            JdbcOperatorDirectory directory,
            Clock clock,
            Duration accessTtl,
            Duration refreshTtl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.accessTtl = Objects.requireNonNull(accessTtl, "accessTtl");
        this.refreshTtl = Objects.requireNonNull(refreshTtl, "refreshTtl");
        if (accessTtl.isNegative() || accessTtl.isZero()) {
            throw new IllegalArgumentException("access token TTL must be positive");
        }
        if (refreshTtl.isNegative() || refreshTtl.isZero()) {
            throw new IllegalArgumentException("refresh token TTL must be positive");
        }
    }

    public Duration accessTtl() {
        return accessTtl;
    }

    /** Issue a new access + refresh pair in a fresh family — 在新家族中签发一对访问/刷新令牌。 */
    public IssuedTokens issue(OperatorPrincipal operator, String userAgent, String clientIp) {
        Objects.requireNonNull(operator, "operator");
        String accountId = requireAccountId(operator.getUsername());
        String familyId = "family-" + UUID.randomUUID();
        return persistPair(operator, accountId, familyId, userAgent, clientIp);
    }

    /**
     * Resolve a live access token to the current operator principal (permissions reloaded).
     * 把仍有效的访问令牌解析为当前操作员主体（权限重新加载）。
     */
    public Optional<OperatorPrincipal> resolveAccessToken(String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawAccessToken.trim());
        Instant now = clock.instant();
        List<TokenRow> rows = jdbc.query(
                """
                SELECT token_id, subject_id, account_id, family_id, expires_at, revoked_at
                FROM operator_access_token
                WHERE token_hash = ?
                """,
                (row, n) -> new TokenRow(
                        row.getString("token_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getString("family_id"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("revoked_at") == null ? null : row.getTimestamp("revoked_at").toInstant()),
                hash);
        if (rows.size() != 1) {
            return Optional.empty();
        }
        TokenRow token = rows.get(0);
        if (token.revokedAt() != null || !token.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return loadEnabledPrincipal(token.accountId());
    }

    /**
     * Rotate refresh: revoke the presented token, issue a new pair in the same family.
     * Reuse of a revoked refresh revokes the entire family and refuses.
     * 轮换刷新：吊销出示的令牌，同族签发新对。重用已吊销刷新则吊销整族并拒绝。
     */
    public IssuedTokens refresh(String rawRefreshToken, String userAgent, String clientIp) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidOperatorTokenException("refresh token is required");
        }
        String hash = sha256Hex(rawRefreshToken.trim());
        List<TokenRow> rows = jdbc.query(
                """
                SELECT token_id, subject_id, account_id, family_id, expires_at, revoked_at
                FROM operator_refresh_token
                WHERE token_hash = ?
                """,
                (row, n) -> new TokenRow(
                        row.getString("token_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getString("family_id"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("revoked_at") == null
                                ? null
                                : row.getTimestamp("revoked_at").toInstant()),
                hash);
        if (rows.size() != 1) {
            throw new InvalidOperatorTokenException("refresh token is unknown");
        }
        TokenRow presented = rows.get(0);
        Instant now = clock.instant();
        // Revoke-family side effects must commit even when we refuse — do them outside the rotate txn.
        // 拒绝时的整族吊销必须落库，不能与抛错同事务回滚。
        if (presented.revokedAt() != null) {
            revokeFamily(presented.familyId(), now);
            throw new InvalidOperatorTokenException("refresh token reuse detected");
        }
        if (!presented.expiresAt().isAfter(now)) {
            revokeFamily(presented.familyId(), now);
            throw new InvalidOperatorTokenException("refresh token expired");
        }
        Optional<OperatorPrincipal> principal = loadEnabledPrincipal(presented.accountId());
        if (principal.isEmpty()) {
            revokeFamily(presented.familyId(), now);
            throw new InvalidOperatorTokenException("operator is disabled or unknown");
        }
        return transaction.execute(status -> {
            // Re-check inside the transaction against concurrent refresh.
            // 事务内再核对，防止并发刷新。
            List<TokenRow> locked = jdbc.query(
                    """
                    SELECT token_id, subject_id, account_id, family_id, expires_at, revoked_at
                    FROM operator_refresh_token
                    WHERE token_hash = ?
                    """,
                    (row, n) -> new TokenRow(
                            row.getString("token_id"),
                            row.getString("subject_id"),
                            row.getString("account_id"),
                            row.getString("family_id"),
                            row.getTimestamp("expires_at").toInstant(),
                            row.getTimestamp("revoked_at") == null
                                    ? null
                                    : row.getTimestamp("revoked_at").toInstant()),
                    hash);
            if (locked.size() != 1 || locked.get(0).revokedAt() != null) {
                status.setRollbackOnly();
                throw new InvalidOperatorTokenException("refresh token reuse detected");
            }
            TokenRow current = locked.get(0);
            Instant rotateAt = clock.instant();
            revokeTokenRow("operator_refresh_token", current.tokenId(), rotateAt);
            jdbc.update(
                    """
                    UPDATE operator_access_token
                    SET revoked_at = ?
                    WHERE family_id = ? AND revoked_at IS NULL
                    """,
                    java.sql.Timestamp.from(rotateAt),
                    current.familyId());
            return persistPair(
                    principal.get(), current.accountId(), current.familyId(), userAgent, clientIp);
        });
    }

    /** Revoke the refresh family (logout) — 按刷新令牌吊销整族（退出）。 */
    public void revokeByRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String hash = sha256Hex(rawRefreshToken.trim());
        List<String> families = jdbc.query(
                "SELECT family_id FROM operator_refresh_token WHERE token_hash = ?",
                (row, n) -> row.getString("family_id"),
                hash);
        if (families.isEmpty()) {
            return;
        }
        revokeFamily(families.get(0), clock.instant());
    }

    /** Revoke every token family for a subject (password change / disable) — 吊销某主体的全部令牌族。 */
    public void revokeAllForSubject(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return;
        }
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE operator_access_token SET revoked_at = ?
                WHERE subject_id = ? AND revoked_at IS NULL
                """,
                java.sql.Timestamp.from(now),
                subjectId);
        jdbc.update(
                """
                UPDATE operator_refresh_token SET revoked_at = ?
                WHERE subject_id = ? AND revoked_at IS NULL
                """,
                java.sql.Timestamp.from(now),
                subjectId);
    }

    private IssuedTokens persistPair(
            OperatorPrincipal operator, String accountId, String familyId, String userAgent, String clientIp) {
        Instant now = clock.instant();
        String accessRaw = randomToken();
        String refreshRaw = randomToken();
        String accessId = "access-" + UUID.randomUUID();
        String refreshId = "refresh-" + UUID.randomUUID();
        Instant accessExpires = now.plus(accessTtl);
        Instant refreshExpires = now.plus(refreshTtl);
        jdbc.update(
                """
                INSERT INTO operator_access_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """,
                accessId,
                sha256Hex(accessRaw),
                operator.subjectId(),
                accountId,
                familyId,
                java.sql.Timestamp.from(accessExpires));
        jdbc.update(
                """
                INSERT INTO operator_refresh_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at,
                     user_agent, client_ip)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """,
                refreshId,
                sha256Hex(refreshRaw),
                operator.subjectId(),
                accountId,
                familyId,
                java.sql.Timestamp.from(refreshExpires),
                truncate(userAgent, 512),
                truncate(clientIp, 64));
        return new IssuedTokens(
                accessRaw, refreshRaw, accessTtl.toSeconds(), accessExpires, refreshExpires, familyId);
    }

    private void revokeFamily(String familyId, Instant when) {
        java.sql.Timestamp ts = java.sql.Timestamp.from(when);
        jdbc.update(
                """
                UPDATE operator_access_token SET revoked_at = ?
                WHERE family_id = ? AND revoked_at IS NULL
                """,
                ts,
                familyId);
        jdbc.update(
                """
                UPDATE operator_refresh_token SET revoked_at = ?
                WHERE family_id = ? AND revoked_at IS NULL
                """,
                ts,
                familyId);
    }

    private void revokeTokenRow(String table, String tokenId, Instant when) {
        jdbc.update(
                "UPDATE " + table + " SET revoked_at = ? WHERE token_id = ? AND revoked_at IS NULL",
                java.sql.Timestamp.from(when),
                tokenId);
    }

    private Optional<OperatorPrincipal> loadEnabledPrincipal(String accountId) {
        List<AccountLogin> logins = jdbc.query(
                "SELECT login_name FROM account WHERE account_id = ?",
                (row, n) -> new AccountLogin(row.getString("login_name")),
                accountId);
        if (logins.size() != 1) {
            return Optional.empty();
        }
        try {
            OperatorPrincipal principal =
                    (OperatorPrincipal) directory.loadUserByUsername(logins.get(0).loginName());
            if (!principal.isEnabled()) {
                return Optional.empty();
            }
            return Optional.of(principal);
        } catch (org.springframework.security.core.userdetails.UsernameNotFoundException ex) {
            return Optional.empty();
        }
    }

    private String requireAccountId(String loginName) {
        List<String> ids = jdbc.query(
                "SELECT account_id FROM account WHERE login_name = ?",
                (row, n) -> row.getString("account_id"),
                loginName);
        if (ids.size() != 1) {
            throw new IllegalArgumentException("operator is unknown");
        }
        return ids.get(0);
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String truncate(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    public record IssuedTokens(
            String accessToken,
            String refreshToken,
            long expiresInSeconds,
            Instant accessExpiresAt,
            Instant refreshExpiresAt,
            String familyId) {}

    private record TokenRow(
            String tokenId,
            String subjectId,
            String accountId,
            String familyId,
            Instant expiresAt,
            Instant revokedAt) {}

    private record AccountLogin(String loginName) {}
}
