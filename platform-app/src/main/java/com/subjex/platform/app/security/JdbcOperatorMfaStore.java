package com.subjex.platform.app.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * JdbcOperatorMfaStore — TOTP 登记、恢复码、登录挑战：密钥密文入库，挑战/恢复码只存摘要。
 * <p>
 * Enroll is two-step (start → confirm). Login after password issues a short-lived MFA challenge when
 * TOTP is confirmed. Recovery codes are single-use.
 * 登记分两步。口令通过后若已确认 TOTP 则发短时挑战。恢复码一次性。
 */
public final class JdbcOperatorMfaStore {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CHALLENGE_BYTES = 32;
    private static final int RECOVERY_COUNT = 10;
    private static final int TOTP_WINDOW = 1;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final JdbcOperatorDirectory directory;
    private final AesGcmSecretCipher cipher;
    private final Clock clock;
    private final Duration challengeTtl;
    private final String issuer;
    private final boolean requiredForAll;
    private final boolean requiredForPlatformOperator;

    public JdbcOperatorMfaStore(
            JdbcTemplate jdbc,
            TransactionTemplate transaction,
            JdbcOperatorDirectory directory,
            AesGcmSecretCipher cipher,
            Clock clock,
            Duration challengeTtl,
            String issuer,
            boolean requiredForAll,
            boolean requiredForPlatformOperator) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transaction = Objects.requireNonNull(transaction, "transaction");
        this.directory = Objects.requireNonNull(directory, "directory");
        this.cipher = Objects.requireNonNull(cipher, "cipher");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.challengeTtl = Objects.requireNonNull(challengeTtl, "challengeTtl");
        this.issuer = Objects.requireNonNull(issuer, "issuer");
        this.requiredForAll = requiredForAll;
        this.requiredForPlatformOperator = requiredForPlatformOperator;
        if (challengeTtl.isNegative() || challengeTtl.isZero()) {
            throw new IllegalArgumentException("MFA challenge TTL must be positive");
        }
        if (issuer.isBlank()) {
            throw new IllegalArgumentException("MFA issuer must not be blank");
        }
    }

    public Duration challengeTtl() {
        return challengeTtl;
    }

    public boolean isEnrolled(String subjectId) {
        if (subjectId == null || subjectId.isBlank()) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM operator_mfa_totp
                WHERE subject_id = ? AND confirmed_at IS NOT NULL
                """,
                Integer.class,
                subjectId);
        return count != null && count > 0;
    }

    /**
     * Whether policy forbids issuing tokens without an enrolled factor.
     * 策略是否禁止在未登记因素时签发令牌。
     */
    public boolean enrollmentRequired(OperatorPrincipal operator) {
        if (operator == null) {
            return false;
        }
        if (requiredForAll) {
            return true;
        }
        if (!requiredForPlatformOperator) {
            return false;
        }
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM subject_role
                WHERE subject_id = ? AND role_name = 'platform-operator'
                """,
                Integer.class,
                operator.subjectId());
        return count != null && count > 0;
    }

    /** Start enrollment: replace any unconfirmed row; refuse if already confirmed. */
    public EnrollmentStart startEnrollment(OperatorPrincipal operator) {
        Objects.requireNonNull(operator, "operator");
        if (isEnrolled(operator.subjectId())) {
            throw new IllegalStateException("MFA is already enrolled");
        }
        String secret = TotpGenerator.generateSecret();
        String encrypted = cipher.encryptUtf8(secret);
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM operator_mfa_totp WHERE subject_id = ? AND confirmed_at IS NULL", operator.subjectId());
            jdbc.update(
                    """
                    INSERT INTO operator_mfa_totp (subject_id, secret_encrypted, confirmed_at, created_at)
                    VALUES (?, ?, NULL, ?)
                    """,
                    operator.subjectId(),
                    encrypted,
                    java.sql.Timestamp.from(now));
        });
        return new EnrollmentStart(
                secret, TotpGenerator.otpauthUri(issuer, operator.getUsername(), secret));
    }

    /**
     * Confirm pending enrollment with a TOTP code; returns one-time recovery codes.
     * 用 TOTP 码确认待登记；返回一次性恢复码。
     */
    public List<String> confirmEnrollment(OperatorPrincipal operator, String code) {
        Objects.requireNonNull(operator, "operator");
        List<String> secrets = jdbc.query(
                """
                SELECT secret_encrypted FROM operator_mfa_totp
                WHERE subject_id = ? AND confirmed_at IS NULL
                """,
                (row, n) -> row.getString("secret_encrypted"),
                operator.subjectId());
        if (secrets.size() != 1) {
            throw new IllegalStateException("no pending MFA enrollment");
        }
        String secret = cipher.decryptUtf8(secrets.get(0));
        if (!TotpGenerator.verify(secret, code, clock.instant(), TOTP_WINDOW)) {
            throw new InvalidOperatorTokenException("invalid TOTP code");
        }
        Instant now = clock.instant();
        List<String> plainCodes = new ArrayList<>(RECOVERY_COUNT);
        transaction.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    UPDATE operator_mfa_totp SET confirmed_at = ?
                    WHERE subject_id = ? AND confirmed_at IS NULL
                    """,
                    java.sql.Timestamp.from(now),
                    operator.subjectId());
            jdbc.update("DELETE FROM operator_mfa_recovery WHERE subject_id = ?", operator.subjectId());
            for (int i = 0; i < RECOVERY_COUNT; i++) {
                String plain = randomRecoveryCode();
                plainCodes.add(plain);
                jdbc.update(
                        """
                        INSERT INTO operator_mfa_recovery
                            (recovery_id, subject_id, code_hash, used_at, created_at)
                        VALUES (?, ?, ?, NULL, ?)
                        """,
                        "recovery-" + UUID.randomUUID(),
                        operator.subjectId(),
                        JdbcOperatorTokenStore.sha256Hex(normalizeRecovery(plain)),
                        java.sql.Timestamp.from(now));
            }
        });
        return List.copyOf(plainCodes);
    }

    /**
     * Disable MFA: requires current password match (caller) and a valid TOTP or unused recovery code.
     * 关闭 MFA：调用方已核口令；此处再核 TOTP 或未用恢复码。
     */
    public void disable(OperatorPrincipal operator, String totpOrRecoveryCode) {
        Objects.requireNonNull(operator, "operator");
        if (!isEnrolled(operator.subjectId())) {
            throw new IllegalStateException("MFA is not enrolled");
        }
        if (!consumeFactor(operator.subjectId(), totpOrRecoveryCode)) {
            throw new InvalidOperatorTokenException("invalid MFA code");
        }
        transaction.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM operator_mfa_recovery WHERE subject_id = ?", operator.subjectId());
            jdbc.update("DELETE FROM operator_mfa_totp WHERE subject_id = ?", operator.subjectId());
            jdbc.update(
                    """
                    UPDATE operator_mfa_challenge SET consumed_at = ?
                    WHERE subject_id = ? AND consumed_at IS NULL
                    """,
                    java.sql.Timestamp.from(clock.instant()),
                    operator.subjectId());
        });
    }

    /** Best-effort login name for a live challenge (audit on failed verify). */
    public java.util.Optional<String> loginNameForChallenge(String rawMfaToken) {
        if (rawMfaToken == null || rawMfaToken.isBlank()) {
            return java.util.Optional.empty();
        }
        String hash = JdbcOperatorTokenStore.sha256Hex(rawMfaToken.trim());
        List<String> logins = jdbc.query(
                """
                SELECT a.login_name
                FROM operator_mfa_challenge c
                JOIN account a ON a.account_id = c.account_id
                WHERE c.token_hash = ? AND c.consumed_at IS NULL
                """,
                (row, n) -> row.getString("login_name"),
                hash);
        return logins.size() == 1 ? java.util.Optional.of(logins.get(0)) : java.util.Optional.empty();
    }

    /** Issue a short-lived MFA challenge after password OK — 口令通过后签发短时 MFA 挑战。 */
    public IssuedChallenge issueChallenge(OperatorPrincipal operator) {
        Objects.requireNonNull(operator, "operator");
        String accountId = requireAccountId(operator.getUsername());
        String raw = randomToken();
        Instant now = clock.instant();
        Instant expires = now.plus(challengeTtl);
        jdbc.update(
                """
                INSERT INTO operator_mfa_challenge
                    (challenge_id, token_hash, subject_id, account_id, expires_at, consumed_at)
                VALUES (?, ?, ?, ?, ?, NULL)
                """,
                "mfa-" + UUID.randomUUID(),
                JdbcOperatorTokenStore.sha256Hex(raw),
                operator.subjectId(),
                accountId,
                java.sql.Timestamp.from(expires));
        return new IssuedChallenge(raw, challengeTtl.toSeconds(), expires);
    }

    /**
     * Consume MFA challenge + TOTP/recovery → operator principal for token issue.
     * 消费挑战 + TOTP/恢复码 → 用于签发令牌的操作员主体。
     */
    public OperatorPrincipal verifyChallenge(String rawMfaToken, String code) {
        if (rawMfaToken == null || rawMfaToken.isBlank() || code == null || code.isBlank()) {
            throw new InvalidOperatorTokenException("MFA challenge and code are required");
        }
        String hash = JdbcOperatorTokenStore.sha256Hex(rawMfaToken.trim());
        Instant now = clock.instant();
        List<ChallengeRow> rows = jdbc.query(
                """
                SELECT challenge_id, subject_id, account_id, expires_at, consumed_at
                FROM operator_mfa_challenge WHERE token_hash = ?
                """,
                (row, n) -> new ChallengeRow(
                        row.getString("challenge_id"),
                        row.getString("subject_id"),
                        row.getString("account_id"),
                        row.getTimestamp("expires_at").toInstant(),
                        row.getTimestamp("consumed_at") == null
                                ? null
                                : row.getTimestamp("consumed_at").toInstant()),
                hash);
        if (rows.size() != 1) {
            throw new InvalidOperatorTokenException("MFA challenge is unknown");
        }
        ChallengeRow challenge = rows.get(0);
        if (challenge.consumedAt() != null || !challenge.expiresAt().isAfter(now)) {
            throw new InvalidOperatorTokenException("MFA challenge is expired or used");
        }
        if (!consumeFactor(challenge.subjectId(), code)) {
            throw new InvalidOperatorTokenException("invalid MFA code");
        }
        int updated = jdbc.update(
                """
                UPDATE operator_mfa_challenge SET consumed_at = ?
                WHERE challenge_id = ? AND consumed_at IS NULL
                """,
                java.sql.Timestamp.from(now),
                challenge.challengeId());
        if (updated != 1) {
            throw new InvalidOperatorTokenException("MFA challenge is expired or used");
        }
        return loadEnabledPrincipal(challenge.accountId())
                .orElseThrow(() -> new InvalidOperatorTokenException("operator is disabled or unknown"));
    }

    private boolean consumeFactor(String subjectId, String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        // Prefer TOTP when it looks like digits.
        if (trimmed.matches("\\d{" + TotpGenerator.DIGITS + "}")) {
            Optional<String> secret = loadConfirmedSecret(subjectId);
            if (secret.isPresent() && TotpGenerator.verify(secret.get(), trimmed, clock.instant(), TOTP_WINDOW)) {
                return true;
            }
        }
        String normalized = normalizeRecovery(trimmed);
        String codeHash = JdbcOperatorTokenStore.sha256Hex(normalized);
        Instant now = clock.instant();
        int updated = jdbc.update(
                """
                UPDATE operator_mfa_recovery SET used_at = ?
                WHERE subject_id = ? AND code_hash = ? AND used_at IS NULL
                """,
                java.sql.Timestamp.from(now),
                subjectId,
                codeHash);
        return updated == 1;
    }

    private Optional<String> loadConfirmedSecret(String subjectId) {
        List<String> secrets = jdbc.query(
                """
                SELECT secret_encrypted FROM operator_mfa_totp
                WHERE subject_id = ? AND confirmed_at IS NOT NULL
                """,
                (row, n) -> row.getString("secret_encrypted"),
                subjectId);
        if (secrets.size() != 1) {
            return Optional.empty();
        }
        return Optional.of(cipher.decryptUtf8(secrets.get(0)));
    }

    private Optional<OperatorPrincipal> loadEnabledPrincipal(String accountId) {
        List<String> logins = jdbc.query(
                "SELECT login_name FROM account WHERE account_id = ?",
                (row, n) -> row.getString("login_name"),
                accountId);
        if (logins.size() != 1) {
            return Optional.empty();
        }
        try {
            OperatorPrincipal principal = (OperatorPrincipal) directory.loadUserByUsername(logins.get(0));
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

    private static String randomToken() {
        byte[] bytes = new byte[CHALLENGE_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String randomRecoveryCode() {
        char[] chars = new char[8];
        for (int i = 0; i < chars.length; i++) {
            chars[i] = RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length()));
        }
        return new String(chars, 0, 4) + "-" + new String(chars, 4, 4);
    }

    private static String normalizeRecovery(String code) {
        return code.trim().toUpperCase(Locale.ROOT).replace("-", "").replace(" ", "");
    }

    public record EnrollmentStart(String secret, String otpauthUri) {}

    public record IssuedChallenge(String mfaToken, long expiresInSeconds, Instant expiresAt) {}

    private record ChallengeRow(
            String challengeId, String subjectId, String accountId, Instant expiresAt, Instant consumedAt) {}
}
