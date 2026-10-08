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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOidcLoginStore — 短时保存 PKCE code_verifier 与 nonce，按 state 哈希查找并一次性消费。
 */
public final class JdbcOidcLoginStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Duration loginTtl;

    public JdbcOidcLoginStore(
            JdbcTemplate jdbc, TransactionTemplate transaction, Clock clock, Duration loginTtl) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.loginTtl = Objects.requireNonNull(loginTtl, "loginTtl");
        if (loginTtl.isNegative() || loginTtl.isZero()) {
            throw new IllegalArgumentException("oidc login TTL must be positive");
        }
    }

    /** Create a pending login and return the public state string — 创建待换码登录并返回公开 state。 */
    public OidcPendingLogin create() {
        String state = randomUrlToken(32);
        String codeVerifier = randomUrlToken(48);
        String nonce = randomUrlToken(24);
        Instant expiresAt = clock.instant().plus(loginTtl);
        jdbc.update(
                """
                INSERT INTO operator_oidc_login (state_hash, code_verifier, nonce, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, NULL)
                """,
                sha256Hex(state),
                codeVerifier,
                nonce,
                java.sql.Timestamp.from(expiresAt));
        return new OidcPendingLogin(state, codeVerifier, nonce, expiresAt);
    }

    /**
     * Consume a pending login by raw state (one-shot). Empty if unknown, expired, or already used.
     * 按原始 state 一次性取出；未知、过期或已用则空。
     */
    public Optional<OidcPendingLogin> consume(String rawState) {
        if (rawState == null || rawState.isBlank()) {
            return Optional.empty();
        }
        String hash = sha256Hex(rawState.trim());
        return transaction.execute(status -> {
            List<OidcPendingLogin> rows = jdbc.query(
                    """
                    SELECT code_verifier, nonce, expires_at, consumed_at
                    FROM operator_oidc_login
                    WHERE state_hash = ?
                    """,
                    (row, n) -> new OidcPendingLogin(
                            rawState.trim(),
                            row.getString("code_verifier"),
                            row.getString("nonce"),
                            row.getTimestamp("expires_at").toInstant()),
                    hash);
            if (rows.size() != 1) {
                return Optional.empty();
            }
            OidcPendingLogin pending = rows.get(0);
            Instant now = clock.instant();
            Integer consumed = jdbc.query(
                    "SELECT CASE WHEN consumed_at IS NULL THEN 0 ELSE 1 END FROM operator_oidc_login WHERE state_hash = ?",
                    (row, n) -> row.getInt(1),
                    hash).stream().findFirst().orElse(1);
            if (consumed != 0 || !pending.expiresAt().isAfter(now)) {
                return Optional.empty();
            }
            int updated = jdbc.update(
                    """
                    UPDATE operator_oidc_login SET consumed_at = ?
                    WHERE state_hash = ? AND consumed_at IS NULL
                    """,
                    java.sql.Timestamp.from(now),
                    hash);
            if (updated != 1) {
                return Optional.empty();
            }
            return Optional.of(pending);
        });
    }

    private static String randomUrlToken(int byteLength) {
        byte[] bytes = new byte[byteLength];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }
}
