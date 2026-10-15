package com.subjex.platform.app.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOauthAuthCodeStore — authorization codes (hashed) + PKCE fields (ID-1).
 * 授权码（仅存摘要）与 PKCE 字段。
 */
public final class JdbcOauthAuthCodeStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcOauthAuthCodeStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record IssuedCode(String rawCode, Instant expiresAt) {}

    public record StoredCode(
            String clientId,
            String subjectId,
            String accountId,
            String loginName,
            String redirectUri,
            String scopes,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce,
            Instant expiresAt,
            Instant consumedAt) {}

    /** Issue raw code (returned once) and persist hash — 签发原始码（只回一次）并持久化摘要。 */
    public IssuedCode issue(
            String clientId,
            String subjectId,
            String accountId,
            String loginName,
            String redirectUri,
            String scopes,
            String codeChallenge,
            String codeChallengeMethod,
            String nonce,
            Instant expiresAt) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO oauth_auth_code (
                  code_hash, client_id, subject_id, account_id, login_name, redirect_uri, scopes,
                  code_challenge, code_challenge_method, nonce, expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                sha256Hex(raw),
                clientId,
                subjectId,
                accountId,
                loginName,
                redirectUri,
                scopes,
                codeChallenge,
                codeChallengeMethod,
                nonce,
                Timestamp.from(expiresAt),
                Timestamp.from(now));
        return new IssuedCode(raw, expiresAt);
    }

    public Optional<StoredCode> findLive(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        List<StoredCode> rows = jdbc.query(
                """
                SELECT client_id, subject_id, account_id, login_name, redirect_uri, scopes,
                       code_challenge, code_challenge_method, nonce, expires_at, consumed_at
                  FROM oauth_auth_code
                 WHERE code_hash = ?
                """,
                (row, n) -> new StoredCode(
                        row.getString("client_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getString("login_name"),
                        row.getString("redirect_uri"),
                        row.getString("scopes"),
                        row.getString("code_challenge"),
                        row.getString("code_challenge_method"),
                        row.getString("nonce"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("consumed_at") == null
                                ? null
                                : row.getTimestamp("consumed_at").toInstant()),
                sha256Hex(rawCode.trim()));
        if (rows.size() != 1) {
            return Optional.empty();
        }
        StoredCode code = rows.get(0);
        if (code.consumedAt() != null || !code.expiresAt().isAfter(now)) {
            return Optional.empty();
        }
        return Optional.of(code);
    }

    public void consume(String rawCode) {
        jdbc.update(
                "UPDATE oauth_auth_code SET consumed_at = ? WHERE code_hash = ?",
                Timestamp.from(clock.instant()),
                sha256Hex(rawCode.trim()));
    }

    public static boolean verifyPkce(String method, String challenge, String verifier) {
        if (challenge == null || verifier == null || verifier.isBlank()) {
            return false;
        }
        if (!"S256".equalsIgnoreCase(method)) {
            return false;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
            return computed.equals(challenge);
        } catch (Exception ex) {
            return false;
        }
    }

    static String sha256Hex(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 required", ex);
        }
    }
}
