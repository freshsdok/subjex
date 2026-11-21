package com.subjex.platform.app.identity.enduser;

import com.subjex.platform.app.security.TenantDisabledException;
import com.subjex.platform.app.security.TenantNotFoundException;
import com.subjex.platform.contract.identity.AccountState;
import com.subjex.platform.contract.identity.IdentityState;
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
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcEndUserTokenStore — opaque end-user access + rotating refresh (ADR 0014 / WP2-2).
 * 终端不透明访问令牌与轮换刷新令牌。
 * <p>
 * Raw tokens use prefixes {@link #ACCESS_PREFIX} / {@link #REFRESH_PREFIX} and are stored as SHA-256
 * only, in {@code end_user_*} tables — never in {@code operator_*} token tables.
 * 原始令牌带 {@link #ACCESS_PREFIX} / {@link #REFRESH_PREFIX} 前缀，只存 SHA-256，写入 {@code end_user_*}，绝不写入操作员令牌表。
 * Refresh reuse of a revoked token revokes the family and does not start a new session.
 * 重用已吊销刷新令牌会吊销整族，不会开启新会话。
 */
public final class JdbcEndUserTokenStore {

    /** Access token prefix — 访问令牌前缀。 */
    public static final String ACCESS_PREFIX = "eu.at.";

    /** Refresh token prefix — 刷新令牌前缀。 */
    public static final String REFRESH_PREFIX = "eu.rt.";

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EndUserAccountStore accounts;
    private final Clock clock;
    private final Duration accessTtl;
    private final Duration refreshTtl;

    public JdbcEndUserTokenStore(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            EndUserAccountStore accounts,
            Clock clock,
            Duration accessTtl,
            Duration refreshTtl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
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

    public Duration refreshTtl() {
        return refreshTtl;
    }

    /**
     * Issue a new pair for an end-user account in an explicit ACTIVE business tenant.
     * Internal helper for later login slices and for tests (no phone/WeChat here).
     * 在显式 ACTIVE 业务租户中为终端账号签发新对。供后续登录切片与测试调用（本切片无手机号/微信）。
     */
    public IssuedTokens issue(String accountId, String tenantId, String userAgent, String clientIp) {
        String aid = requireId(accountId, "accountId");
        String tid = EndUserAccountStore.requireExplicitBusinessTenantId(tenantId);
        EndUserRecord record = requireIssuableAccount(aid, tid);
        String familyId = "eu-family-" + UUID.randomUUID();
        return persistPair(record, familyId, userAgent, clientIp);
    }

    /**
     * Resolve a live {@code eu.at.} access token to an {@link EndUserPrincipal}.
     * 把仍有效的 {@code eu.at.} 访问令牌解析为终端主体。
     */
    public Optional<EndUserPrincipal> resolveAccessToken(String rawAccessToken) {
        if (rawAccessToken == null || rawAccessToken.isBlank()) {
            return Optional.empty();
        }
        String raw = rawAccessToken.trim();
        if (!raw.startsWith(ACCESS_PREFIX)) {
            return Optional.empty();
        }
        Optional<TokenRow> row = findToken("end_user_access_token", raw);
        Instant now = clock.instant();
        if (row.isEmpty()) {
            return Optional.empty();
        }
        TokenRow token = row.get();
        if (token.revokedAt() != null || !token.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return loadEnabledPrincipal(token.accountId());
    }

    /**
     * Rotate refresh: revoke the presented token, issue a new pair in the same family.
     * Reuse of a revoked refresh revokes the entire family and refuses a new session.
     * 轮换刷新：吊销出示的令牌，同族签发新对。重用已吊销刷新则吊销整族并拒绝新会话。
     */
    public IssuedTokens refresh(String rawRefreshToken, String userAgent, String clientIp) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new InvalidEndUserTokenException("refresh token is required");
        }
        String raw = rawRefreshToken.trim();
        if (!raw.startsWith(REFRESH_PREFIX)) {
            throw new InvalidEndUserTokenException("refresh token is unknown");
        }
        String hash = sha256Hex(raw);
        List<TokenRow> rows = queryTokens("end_user_refresh_token", hash);
        if (rows.size() != 1) {
            throw new InvalidEndUserTokenException("refresh token is unknown");
        }
        TokenRow presented = rows.get(0);
        Instant now = clock.instant();
        // Revoke-family side effects must commit even when we refuse — do them outside the rotate txn.
        // 拒绝时的整族吊销必须落库，不能与抛错同事务回滚。
        if (presented.revokedAt() != null) {
            revokeFamily(presented.familyId(), now);
            throw new InvalidEndUserTokenException("refresh token reuse detected");
        }
        if (!presented.expiresAt().isAfter(now)) {
            revokeFamily(presented.familyId(), now);
            throw new InvalidEndUserTokenException("refresh token expired");
        }
        Optional<EndUserRecord> record = loadIssuableAccount(presented.accountId());
        if (record.isEmpty()) {
            revokeFamily(presented.familyId(), now);
            throw new InvalidEndUserTokenException("end-user is disabled or unknown");
        }
        return transaction.execute(status -> {
            List<TokenRow> locked = queryTokens("end_user_refresh_token", hash);
            if (locked.size() != 1 || locked.get(0).revokedAt() != null) {
                status.setRollbackOnly();
                throw new InvalidEndUserTokenException("refresh token reuse detected");
            }
            TokenRow current = locked.get(0);
            Instant rotateAt = clock.instant();
            revokeTokenRow("end_user_refresh_token", current.tokenId(), rotateAt);
            jdbc.update(
                    """
                    UPDATE end_user_access_token
                    SET revoked_at = ?
                    WHERE family_id = ? AND revoked_at IS NULL
                    """,
                    java.sql.Timestamp.from(rotateAt),
                    current.familyId());
            return persistPair(record.get(), current.familyId(), userAgent, clientIp);
        });
    }

    /**
     * Revoke the refresh family (logout). Unknown or already-revoked tokens are not a new session.
     * 按刷新令牌吊销整族（退出）。未知或已吊销的令牌不会开启新会话。
     */
    public void revokeByRefreshToken(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        String raw = rawRefreshToken.trim();
        if (!raw.startsWith(REFRESH_PREFIX)) {
            return;
        }
        String hash = sha256Hex(raw);
        List<String> families = jdbc.query(
                "SELECT family_id FROM end_user_refresh_token WHERE token_hash = ?",
                (row, n) -> row.getString("family_id"),
                hash);
        if (families.isEmpty()) {
            return;
        }
        revokeFamily(families.get(0), clock.instant());
    }

    private EndUserRecord requireIssuableAccount(String accountId, String tenantId) {
        requireActiveBusinessTenant(tenantId);
        if (accounts.findActiveIdentity(accountId, tenantId).isEmpty()) {
            throw new IllegalArgumentException("no active end-user identity in tenant: " + tenantId);
        }
        return loadIssuableAccount(accountId)
                .orElseThrow(() -> new IllegalArgumentException("end-user account not found: " + accountId));
    }

    private Optional<EndUserRecord> loadIssuableAccount(String accountId) {
        Optional<EndUserRecord> record = accounts.findByAccountId(accountId);
        if (record.isEmpty() || record.get().account().accountState() != AccountState.ACTIVE) {
            return Optional.empty();
        }
        Integer activeHomes = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM subject_identity si
                JOIN tenant t ON t.tenant_id = si.tenant_id
                WHERE si.account_id = ?
                  AND si.identity_state = ?
                  AND t.tenant_state = 'ACTIVE'
                  AND si.tenant_id <> ?
                """,
                Integer.class,
                accountId,
                IdentityState.ACTIVE.name(),
                EndUserAccountStore.RESERVED_TENANT);
        if (activeHomes == null || activeHomes == 0) {
            return Optional.empty();
        }
        return record;
    }

    private void requireActiveBusinessTenant(String tenantId) {
        List<String> states = jdbc.query(
                "SELECT tenant_state FROM tenant WHERE tenant_id = ?",
                (row, n) -> row.getString("tenant_state"),
                tenantId);
        if (states.isEmpty()) {
            throw new TenantNotFoundException(tenantId);
        }
        String state = states.get(0) == null ? "" : states.get(0).trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(state)) {
            throw new TenantDisabledException(tenantId);
        }
    }

    private IssuedTokens persistPair(EndUserRecord record, String familyId, String userAgent, String clientIp) {
        Instant now = clock.instant();
        String accessRaw = ACCESS_PREFIX + randomToken();
        String refreshRaw = REFRESH_PREFIX + randomToken();
        String accessId = "eu-access-" + UUID.randomUUID();
        String refreshId = "eu-refresh-" + UUID.randomUUID();
        Instant accessExpires = now.plus(accessTtl);
        Instant refreshExpires = now.plus(refreshTtl);
        String subjectId = record.subject().subjectId();
        String accountId = record.account().accountId();
        jdbc.update(
                """
                INSERT INTO end_user_access_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at)
                VALUES (?, ?, ?, ?, ?, ?, NULL)
                """,
                accessId,
                sha256Hex(accessRaw),
                subjectId,
                accountId,
                familyId,
                java.sql.Timestamp.from(accessExpires));
        jdbc.update(
                """
                INSERT INTO end_user_refresh_token
                    (token_id, token_hash, subject_id, account_id, family_id, expires_at, revoked_at,
                     user_agent, client_ip)
                VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)
                """,
                refreshId,
                sha256Hex(refreshRaw),
                subjectId,
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
                UPDATE end_user_access_token SET revoked_at = ?
                WHERE family_id = ? AND revoked_at IS NULL
                """,
                ts,
                familyId);
        jdbc.update(
                """
                UPDATE end_user_refresh_token SET revoked_at = ?
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

    private Optional<EndUserPrincipal> loadEnabledPrincipal(String accountId) {
        return loadIssuableAccount(accountId)
                .map(record -> new EndUserPrincipal(
                        record.account().accountId(),
                        record.subject().subjectId(),
                        record.account().loginName(),
                        record.account().accountState() == AccountState.ACTIVE));
    }

    private Optional<TokenRow> findToken(String table, String raw) {
        List<TokenRow> rows = queryTokens(table, sha256Hex(raw));
        return rows.size() == 1 ? Optional.of(rows.get(0)) : Optional.empty();
    }

    private List<TokenRow> queryTokens(String table, String hash) {
        return jdbc.query(
                "SELECT token_id, subject_id, account_id, family_id, expires_at, revoked_at FROM "
                        + table
                        + " WHERE token_hash = ?",
                (row, n) -> new TokenRow(
                        row.getString("token_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getString("family_id"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("revoked_at") == null ? null : row.getTimestamp("revoked_at").toInstant()),
                hash);
    }

    private static String requireId(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return value.trim();
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
}
