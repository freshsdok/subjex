package com.subjex.platform.app.identity;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * JdbcOauthConsentStore — persist / list / revoke subject consents (ID-3).
 * 持久化 / 列出 / 吊销主体同意。
 */
public final class JdbcOauthConsentStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public JdbcOauthConsentStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public record ConsentGrant(String subjectId, String clientId, String scopes, Instant grantedAt, Instant revokedAt) {
        public boolean active() {
            return revokedAt == null;
        }

        public Set<String> scopeSet() {
            return split(scopes);
        }
    }

    public Optional<ConsentGrant> findActive(String subjectId, String clientId) {
        return find(subjectId, clientId).filter(ConsentGrant::active);
    }

    public Optional<ConsentGrant> find(String subjectId, String clientId) {
        if (subjectId == null || clientId == null) {
            return Optional.empty();
        }
        List<ConsentGrant> rows = jdbc.query(
                """
                SELECT subject_id, client_id, scopes, granted_at, revoked_at
                  FROM oauth_consent
                 WHERE subject_id = ? AND client_id = ?
                """,
                (row, n) -> new ConsentGrant(
                        row.getString("subject_id"),
                        row.getString("client_id"),
                        row.getString("scopes"),
                        row.getTimestamp("granted_at").toInstant(),
                        row.getTimestamp("revoked_at") == null
                                ? null
                                : row.getTimestamp("revoked_at").toInstant()),
                subjectId,
                clientId);
        return rows.stream().findFirst();
    }

    public List<ConsentGrant> listForSubject(String subjectId) {
        return jdbc.query(
                """
                SELECT subject_id, client_id, scopes, granted_at, revoked_at
                  FROM oauth_consent
                 WHERE subject_id = ?
                 ORDER BY granted_at DESC
                """,
                (row, n) -> new ConsentGrant(
                        row.getString("subject_id"),
                        row.getString("client_id"),
                        row.getString("scopes"),
                        row.getTimestamp("granted_at").toInstant(),
                        row.getTimestamp("revoked_at") == null
                                ? null
                                : row.getTimestamp("revoked_at").toInstant()),
                subjectId);
    }

    /** Upsert active consent (merge scopes) — 写入或合并范围并清吊销。 */
    public void grant(String subjectId, String clientId, Set<String> scopes) {
        Objects.requireNonNull(subjectId, "subjectId");
        Objects.requireNonNull(clientId, "clientId");
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes required");
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(scopes);
        find(subjectId, clientId).ifPresent(existing -> merged.addAll(existing.scopeSet()));
        String raw = merged.stream().sorted().collect(Collectors.joining(" "));
        Timestamp now = Timestamp.from(clock.instant());
        int updated = jdbc.update(
                """
                UPDATE oauth_consent
                   SET scopes = ?, granted_at = ?, revoked_at = NULL
                 WHERE subject_id = ? AND client_id = ?
                """,
                raw,
                now,
                subjectId,
                clientId);
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO oauth_consent (subject_id, client_id, scopes, granted_at, revoked_at)
                    VALUES (?, ?, ?, ?, NULL)
                    """,
                    subjectId,
                    clientId,
                    raw,
                    now);
        }
    }

    public boolean revoke(String subjectId, String clientId) {
        Timestamp now = Timestamp.from(clock.instant());
        return jdbc.update(
                        """
                        UPDATE oauth_consent
                           SET revoked_at = ?
                         WHERE subject_id = ? AND client_id = ? AND revoked_at IS NULL
                        """,
                        now,
                        subjectId,
                        clientId)
                > 0;
    }

    /** Whether active consent covers every requested scope — 有效同意是否覆盖全部请求范围。 */
    public boolean covers(String subjectId, String clientId, Set<String> requested) {
        Optional<ConsentGrant> grant = findActive(subjectId, clientId);
        if (grant.isEmpty()) {
            return false;
        }
        Set<String> have = grant.get().scopeSet();
        return have.containsAll(requested);
    }

    static Set<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.trim().split("\\s+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
