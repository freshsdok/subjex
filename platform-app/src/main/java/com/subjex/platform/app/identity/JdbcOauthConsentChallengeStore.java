package com.subjex.platform.app.identity;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOauthConsentChallengeStore — pending authorize params awaiting consent (ID-3).
 * 待同意的授权请求参数。
 */
public final class JdbcOauthConsentChallengeStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcOauthConsentChallengeStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record Challenge(
            String challengeId,
            String subjectId,
            String accountId,
            String loginName,
            String clientId,
            String redirectUri,
            String scopesRequested,
            String codeChallenge,
            String codeChallengeMethod,
            String state,
            String nonce,
            Instant expiresAt,
            Instant consumedAt) {
        public boolean live(Instant now) {
            return consumedAt == null && expiresAt.isAfter(now);
        }
    }

    public Challenge create(
            String subjectId,
            String accountId,
            String loginName,
            String clientId,
            String redirectUri,
            String scopesRequested,
            String codeChallenge,
            String codeChallengeMethod,
            String state,
            String nonce,
            Instant expiresAt) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = clock.instant();
        jdbc.update(
                """
                INSERT INTO oauth_consent_challenge (
                  challenge_id, subject_id, account_id, login_name, client_id, redirect_uri,
                  scopes_requested, code_challenge, code_challenge_method, state, nonce,
                  expires_at, consumed_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                """,
                id,
                subjectId,
                accountId,
                loginName,
                clientId,
                redirectUri,
                scopesRequested,
                codeChallenge,
                codeChallengeMethod,
                state,
                nonce,
                Timestamp.from(expiresAt),
                Timestamp.from(now));
        return find(id).orElseThrow();
    }

    public Optional<Challenge> findLive(String challengeId) {
        return find(challengeId).filter(c -> c.live(clock.instant()));
    }

    public Optional<Challenge> find(String challengeId) {
        if (challengeId == null || challengeId.isBlank()) {
            return Optional.empty();
        }
        List<Challenge> rows = jdbc.query(
                """
                SELECT challenge_id, subject_id, account_id, login_name, client_id, redirect_uri,
                       scopes_requested, code_challenge, code_challenge_method, state, nonce,
                       expires_at, consumed_at
                  FROM oauth_consent_challenge
                 WHERE challenge_id = ?
                """,
                (row, n) -> new Challenge(
                        row.getString("challenge_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getString("login_name"),
                        row.getString("client_id"),
                        row.getString("redirect_uri"),
                        row.getString("scopes_requested"),
                        row.getString("code_challenge"),
                        row.getString("code_challenge_method"),
                        row.getString("state"),
                        row.getString("nonce"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("consumed_at") == null
                                ? null
                                : row.getTimestamp("consumed_at").toInstant()),
                challengeId.trim());
        return rows.stream().findFirst();
    }

    public void consume(String challengeId) {
        jdbc.update(
                "UPDATE oauth_consent_challenge SET consumed_at = ? WHERE challenge_id = ?",
                Timestamp.from(clock.instant()),
                challengeId.trim());
    }
}
