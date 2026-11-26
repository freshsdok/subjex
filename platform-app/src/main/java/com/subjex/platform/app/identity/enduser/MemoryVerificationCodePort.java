package com.subjex.platform.app.identity.enduser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MemoryVerificationCodePort — in-memory codes for tests and {@code local} (WP2-3).
 * 内存验证码，供测试与 {@code local} 使用。
 * <p>
 * Codes are stored as SHA-256. Plaintext is kept only when {@code revealInResponse} is true
 * (dev peek / local HTTP). Production must not construct this port with reveal enabled, and must
 * use a sender that is {@link VerificationCodeSender#configured()}.
 * 验证码只存 SHA-256。仅在允许出示时保留明文。生产不得开启出示，且必须配置真实发送器。
 */
public final class MemoryVerificationCodePort implements VerificationCodePort {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int CODE_DIGITS = 6;

    private final Clock clock;
    private final Duration ttl;
    private final boolean revealInResponse;
    private final VerificationCodeSender sender;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> plaintextForDev = new ConcurrentHashMap<>();

    public MemoryVerificationCodePort(
            Clock clock, Duration ttl, boolean revealInResponse, VerificationCodeSender sender) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        this.revealInResponse = revealInResponse;
        this.sender = Objects.requireNonNull(sender, "sender");
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("verification-code TTL must be positive");
        }
    }

    @Override
    public Duration ttl() {
        return ttl;
    }

    @Override
    public boolean revealInResponse() {
        return revealInResponse;
    }

    @Override
    public IssuedCode issue(String destination, String purpose) {
        String dest = requireDestination(destination);
        String usedFor = VerificationCodePort.requirePurpose(purpose);
        if (!sender.configured()) {
            throw new VerificationCodeSenderUnconfiguredException();
        }
        String code = randomCode();
        String key = key(dest, usedFor);
        Instant expiresAt = clock.instant().plus(ttl);
        entries.put(key, new Entry(sha256(code), expiresAt));
        if (revealInResponse) {
            plaintextForDev.put(key, code);
        } else {
            plaintextForDev.remove(key);
        }
        sender.send(dest, usedFor, code);
        String revealed = revealInResponse ? code : null;
        return new IssuedCode(dest, usedFor, ttl.toSeconds(), revealed);
    }

    @Override
    public boolean verifyAndConsume(String destination, String purpose, String code) {
        if (destination == null || destination.isBlank() || purpose == null || code == null || code.isBlank()) {
            return false;
        }
        String dest;
        String usedFor;
        try {
            dest = requireDestination(destination);
            usedFor = VerificationCodePort.requirePurpose(purpose);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        String key = key(dest, usedFor);
        Entry entry = entries.get(key);
        Instant now = clock.instant();
        if (entry == null || !entry.expiresAt().isAfter(now)) {
            if (entry != null) {
                entries.remove(key, entry);
                plaintextForDev.remove(key);
            }
            return false;
        }
        if (!MessageDigest.isEqual(entry.codeHash(), sha256(code.trim()))) {
            return false;
        }
        entries.remove(key);
        plaintextForDev.remove(key);
        return true;
    }

    @Override
    public Optional<String> peekForDev(String destination, String purpose) {
        if (!revealInResponse || destination == null || purpose == null) {
            return Optional.empty();
        }
        try {
            String key = key(requireDestination(destination), VerificationCodePort.requirePurpose(purpose));
            Entry entry = entries.get(key);
            if (entry == null || !entry.expiresAt().isAfter(clock.instant())) {
                return Optional.empty();
            }
            return Optional.ofNullable(plaintextForDev.get(key));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /** Same cap as a stored profile email — 与资料邮箱列宽一致. */
    static final int MAX_DESTINATION_LENGTH = 256;

    private static String requireDestination(String destination) {
        if (destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("destination is required");
        }
        String value = destination.trim();
        if (value.length() > MAX_DESTINATION_LENGTH) {
            throw new IllegalArgumentException(
                    "destination must be at most " + MAX_DESTINATION_LENGTH + " characters");
        }
        return value;
    }

    private static String key(String destination, String purpose) {
        return purpose + "|" + destination;
    }

    private static String randomCode() {
        int n = RANDOM.nextInt(1_000_000);
        return String.format("%0" + CODE_DIGITS + "d", n);
    }

    private static byte[] sha256(String raw) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private record Entry(byte[] codeHash, Instant expiresAt) {}
}
